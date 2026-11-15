package com.example.realtimedevboard.board;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** Scenario 11 through the real API and the real Docker sandbox. Requires Docker. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ExecutionApiIT {

    @LocalServerPort
    int port;
    @Autowired
    BoardRepository boards;

    @Test
    void systemStaysResponsiveDuringAndAfterLimitViolation() throws Exception {
        long board = boards.findAll().stream().filter(b -> b.getName().equals("Alpha board")).findFirst().orElseThrow().getId();
        TestSession alice = TestSession.login(port, "alice", BoardApiTest.PASSWORD);
        String loop = "public class Main { public static void main(String[] a) { while (true) { } } }";
        String created = alice.send("POST", "/api/boards/" + board + "/elements",
                "{\"type\":\"CODE\",\"x\":1,\"y\":1,\"width\":300,\"height\":200,\"content\":\"" + loop + "\"}").body();
        String id = created.replaceAll(".*\"id\":(\\d+).*", "$1");

        CompletableFuture<HttpResponse<String>> running = CompletableFuture.supplyAsync(() -> {
            try {
                return alice.send("POST", "/api/boards/" + board + "/elements/" + id + "/run", null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(3000); // the program is now compiling or running inside its container
        assertThat(running).isNotDone();
        long t0 = System.nanoTime();
        assertThat(TestSession.anonymous(port).send("GET", "/api/health", null).body()).contains("UP");
        assertThat(alice.send("GET", "/api/boards/" + board, null).statusCode()).isEqualTo(200);
        assertThat((System.nanoTime() - t0) / 1_000_000).as("API latency while user code runs").isLessThan(2000);

        HttpResponse<String> result = running.get();
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).contains("\"status\":\"TIMEOUT\"");
        // result is stored on the element (survives reload)
        assertThat(alice.send("GET", "/api/boards/" + board, null).body()).contains("TIMEOUT");

        String helloElement = alice.send("POST", "/api/boards/" + board + "/elements",
                "{\"type\":\"CODE\",\"x\":1,\"y\":1,\"width\":300,\"height\":200,\"content\":"
                        + "\"public class Main { public static void main(String[] a) { System.out.println(\\\"Hello\\\"); } }\"}").body();
        String helloId = helloElement.replaceAll(".*\"id\":(\\d+).*", "$1");
        String after = alice.send("POST", "/api/boards/" + board + "/elements/" + helloId + "/run", null).body();
        assertThat(after).contains("\"status\":\"SUCCESS\"", "\"stdout\":\"Hello\\n\"");
    }
}
