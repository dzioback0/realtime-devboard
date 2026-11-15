package com.example.realtimedevboard.board;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

/** A logged-in browser-like HTTP client (session cookie + CSRF header) for API tests. */
class TestSession {

    final String base;
    final CookieManager cookies = new CookieManager();
    final HttpClient http;
    boolean sendCsrf = true;

    private TestSession(int port) {
        base = "http://localhost:" + port;
        http = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static TestSession anonymous(int port) {
        return new TestSession(port);
    }

    static TestSession login(int port, String username, String password) throws Exception {
        TestSession s = new TestSession(port);
        String page = s.send("GET", "/login", null).body();
        Matcher m = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(page);
        if (!m.find()) {
            throw new IllegalStateException("No CSRF token on login page");
        }
        String form = "username=" + enc(username) + "&password=" + enc(password) + "&_csrf=" + enc(m.group(1));
        HttpResponse<String> r = s.http.send(HttpRequest.newBuilder(URI.create(s.base + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        String location = r.headers().firstValue("Location").orElse("");
        if (r.statusCode() != 302 || location.contains("error")) {
            throw new IllegalStateException("Login failed for " + username + ": " + r.statusCode() + " " + location);
        }
        s.send("GET", "/api/me", null); // makes the server issue the XSRF-TOKEN cookie for the new session
        return s;
    }

    HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            b.header("Content-Type", "application/json");
        }
        String csrf = cookie("XSRF-TOKEN");
        if (csrf != null && sendCsrf) {
            b.header("X-XSRF-TOKEN", csrf);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    String cookie(String name) {
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals(name))
                .map(HttpCookie::getValue).findFirst().orElse(null);
    }

    /** Opens /ws/boards/{id}; received messages are queued. Fails (CompletionException) when the handshake is refused. */
    Socket connect(long boardId, String origin) {
        Socket socket = new Socket();
        WebSocket.Builder builder = http.newWebSocketBuilder().header("Cookie", "JSESSIONID=" + cookie("JSESSIONID"));
        if (origin != null) {
            builder.header("Origin", origin);
        }
        socket.ws = builder.buildAsync(URI.create(base.replace("http", "ws") + "/ws/boards/" + boardId), socket).join();
        return socket;
    }

    static class Socket implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> closed = new LinkedBlockingQueue<>();
        WebSocket ws;
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.add(statusCode);
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, UTF_8);
    }
}
