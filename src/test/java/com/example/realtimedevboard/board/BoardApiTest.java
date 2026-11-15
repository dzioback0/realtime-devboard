package com.example.realtimedevboard.board;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Auth, tenant isolation (REST + WebSocket) and realtime sync against a real server with seeded demo data.
 * No Docker needed: run endpoints are only called for boards the caller must NOT access.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BoardApiTest {

    static final String PASSWORD = "test-password";

    @LocalServerPort
    int port;
    @Autowired
    BoardRepository boards;
    @Autowired
    BoardElementRepository elements;

    long alphaBoard;
    long betaBoard;
    long betaElement;
    long betaWorkspace;

    @BeforeEach
    void ids() {
        alphaBoard = boards.findAll().stream().filter(b -> b.getName().equals("Alpha board")).findFirst().orElseThrow().getId();
        Board beta = boards.findAll().stream().filter(b -> b.getName().equals("Beta secret board")).findFirst().orElseThrow();
        betaBoard = beta.getId();
        betaElement = elements.findByBoardIdOrderById(betaBoard).getFirst().getId();
        betaWorkspace = boards.findByIdAndWorkspace_Members_Username(betaBoard, "bob").orElseThrow().getWorkspace().getId();
    }

    @Test
    void anonymousApiCallsGet401AndHealthIsPublic() throws Exception {
        TestSession anon = TestSession.anonymous(port);
        assertThat(anon.send("GET", "/api/workspaces", null).statusCode()).isEqualTo(401);
        assertThat(anon.send("GET", "/api/health", null).body()).contains("UP");
        assertThatThrownBy(() -> TestSession.login(port, "alice", "wrong")).hasMessageContaining("Login failed");
    }

    @Test
    void userSeesOnlyOwnWorkspaces() throws Exception {
        String alice = TestSession.login(port, "alice", PASSWORD).send("GET", "/api/workspaces", null).body();
        assertThat(alice).contains("Team Alpha", "Alpha board").doesNotContain("Team Beta", "Beta secret");
        String bob = TestSession.login(port, "bob", PASSWORD).send("GET", "/api/workspaces", null).body();
        assertThat(bob).contains("Team Beta").doesNotContain("Team Alpha");
    }

    // Scenario 12: another tenant's board cannot be read, changed or executed, even with valid ids
    @Test
    void otherWorkspaceIsInaccessibleViaRest() throws Exception {
        TestSession alice = TestSession.login(port, "alice", PASSWORD);
        String element = "{\"type\":\"TEXT\",\"x\":1,\"y\":1,\"width\":100,\"height\":50,\"content\":\"hacked\"}";

        assertThat(alice.send("GET", "/api/boards/" + betaBoard, null).statusCode()).isEqualTo(404);
        assertThat(alice.send("POST", "/api/boards/" + betaBoard + "/elements", element).statusCode()).isEqualTo(404);
        assertThat(alice.send("PUT", "/api/boards/" + betaBoard + "/elements/" + betaElement, element).statusCode()).isEqualTo(404);
        // own board id + foreign element id must not work either
        assertThat(alice.send("PUT", "/api/boards/" + alphaBoard + "/elements/" + betaElement, element).statusCode()).isEqualTo(404);
        assertThat(alice.send("POST", "/api/boards/" + betaBoard + "/elements/" + betaElement + "/run", null).statusCode()).isEqualTo(404);
        assertThat(alice.send("DELETE", "/api/boards/" + betaBoard + "/elements/" + betaElement, null).statusCode()).isEqualTo(404);
        assertThat(alice.send("POST", "/api/workspaces/" + betaWorkspace + "/boards", "{\"name\":\"x\"}").statusCode()).isEqualTo(404);

        assertThat(elements.findById(betaElement).orElseThrow().getContent()).isEqualTo("Beta only");
        assertThat(TestSession.login(port, "bob", PASSWORD).send("GET", "/api/boards/" + betaBoard, null).statusCode()).isEqualTo(200);
    }

    @Test
    void otherWorkspaceIsInaccessibleViaWebSocket() throws Exception {
        TestSession alice = TestSession.login(port, "alice", PASSWORD);
        assertThatThrownBy(() -> alice.connect(betaBoard, null)).isInstanceOf(CompletionException.class);
        assertThatThrownBy(() -> TestSession.anonymous(port).connect(alphaBoard, null)).isInstanceOf(CompletionException.class);
        // cross-site page cannot open a socket with the user's cookie
        assertThatThrownBy(() -> alice.connect(alphaBoard, "http://evil.example")).isInstanceOf(CompletionException.class);

        // alice is subscribed to her own board only: bob's changes on Beta never reach her
        TestSession.Socket aliceSocket = alice.connect(alphaBoard, null);
        TestSession bob = TestSession.login(port, "bob", PASSWORD);
        bob.send("POST", "/api/boards/" + betaBoard + "/elements",
                "{\"type\":\"TEXT\",\"x\":5,\"y\":5,\"width\":100,\"height\":50,\"content\":\"beta change\"}");
        assertThat(aliceSocket.messages.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void socketIsReadOnly() throws Exception {
        TestSession.Socket socket = TestSession.login(port, "alice", PASSWORD).connect(alphaBoard, null);
        socket.ws.sendText("{\"type\":\"delete\",\"id\":1}", true);
        assertThat(socket.closed.poll(5, TimeUnit.SECONDS)).isEqualTo(1008);
    }

    @Test
    void changesReachOtherMemberInRealtimeAndArePersisted() throws Exception {
        TestSession alice = TestSession.login(port, "alice", PASSWORD);
        TestSession adam = TestSession.login(port, "adam", PASSWORD);
        TestSession.Socket adamSocket = adam.connect(alphaBoard, null);

        var created = alice.send("POST", "/api/boards/" + alphaBoard + "/elements",
                "{\"type\":\"SHAPE\",\"x\":10,\"y\":20,\"width\":120,\"height\":80,\"content\":\"\",\"shape\":\"RECT\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        String id = created.body().replaceAll(".*\"id\":(\\d+).*", "$1");
        assertThat(adamSocket.messages.poll(5, TimeUnit.SECONDS)).contains("\"upsert\"", "\"shape\":\"RECT\"", "\"SHAPE\"");

        // move + resize + change kind + label inside the shape
        alice.send("PUT", "/api/boards/" + alphaBoard + "/elements/" + id,
                "{\"type\":\"SHAPE\",\"x\":300,\"y\":20,\"width\":250,\"height\":180,\"content\":\"API\",\"shape\":\"CIRCLE\"}");
        assertThat(adamSocket.messages.poll(5, TimeUnit.SECONDS))
                .contains("\"x\":300", "\"width\":250", "\"shape\":\"CIRCLE\"", "\"content\":\"API\"");
        // persisted: a fresh load (page refresh) sees the change
        assertThat(adam.send("GET", "/api/boards/" + alphaBoard, null).body()).contains("\"width\":250", "\"content\":\"API\"");

        // shape kind is validated
        assertThat(alice.send("PUT", "/api/boards/" + alphaBoard + "/elements/" + id,
                "{\"type\":\"SHAPE\",\"x\":1,\"y\":1,\"width\":100,\"height\":50,\"shape\":\"STAR\"}").statusCode()).isEqualTo(400);
        assertThat(alice.send("POST", "/api/boards/" + alphaBoard + "/elements",
                "{\"type\":\"SHAPE\",\"x\":1,\"y\":1,\"width\":100,\"height\":50}").statusCode()).isEqualTo(400);

        assertThat(alice.send("DELETE", "/api/boards/" + alphaBoard + "/elements/" + id, null).statusCode()).isEqualTo(204);
        assertThat(adamSocket.messages.poll(5, TimeUnit.SECONDS)).contains("\"delete\"", "\"id\":" + id);

        // arrow: start point (x, y) and end point (endX, endY)
        var arrow = alice.send("POST", "/api/boards/" + alphaBoard + "/elements",
                "{\"type\":\"ARROW\",\"x\":10,\"y\":10,\"width\":20,\"height\":20,\"endX\":200,\"endY\":90}");
        assertThat(arrow.statusCode()).isEqualTo(201);
        String arrowId = arrow.body().replaceAll(".*\"id\":(\\d+).*", "$1");
        assertThat(adamSocket.messages.poll(5, TimeUnit.SECONDS)).contains("\"ARROW\"", "\"endX\":200", "\"endY\":90");
        alice.send("PUT", "/api/boards/" + alphaBoard + "/elements/" + arrowId,
                "{\"type\":\"ARROW\",\"x\":10,\"y\":10,\"width\":20,\"height\":20,\"endX\":400,\"endY\":5}");
        assertThat(adamSocket.messages.poll(5, TimeUnit.SECONDS)).contains("\"endX\":400", "\"endY\":5");
        assertThat(alice.send("POST", "/api/boards/" + alphaBoard + "/elements",
                "{\"type\":\"ARROW\",\"x\":10,\"y\":10,\"width\":20,\"height\":20}").statusCode()).isEqualTo(400);
        assertThat(alice.send("POST", "/api/boards/" + alphaBoard + "/elements",
                "{\"type\":\"ARROW\",\"x\":10,\"y\":10,\"width\":20,\"height\":20,\"endX\":-1,\"endY\":5}").statusCode()).isEqualTo(400);

        // arrow ends attach only to non-arrow elements of the same board
        String rect = alice.send("POST", "/api/boards/" + alphaBoard + "/elements",
                "{\"type\":\"SHAPE\",\"x\":500,\"y\":500,\"width\":100,\"height\":60,\"shape\":\"RECT\"}").body()
                .replaceAll(".*\"id\":(\\d+).*", "$1");
        String attach = "{\"type\":\"ARROW\",\"x\":10,\"y\":10,\"width\":20,\"height\":20,\"endX\":500,\"endY\":530,\"endId\":%s}";
        assertThat(alice.send("PUT", "/api/boards/" + alphaBoard + "/elements/" + arrowId, attach.formatted(rect)).body())
                .contains("\"endId\":" + rect, "\"startId\":null");
        assertThat(alice.send("PUT", "/api/boards/" + alphaBoard + "/elements/" + arrowId, attach.formatted(betaElement))
                .statusCode()).as("attach to another workspace's element").isEqualTo(400);
        assertThat(alice.send("PUT", "/api/boards/" + alphaBoard + "/elements/" + arrowId, attach.formatted(arrowId))
                .statusCode()).as("attach to an arrow").isEqualTo(400);
        assertThat(elements.findById(Long.valueOf(arrowId)).orElseThrow().getEndId()).isEqualTo(Long.valueOf(rect));
    }

    @Test
    void invalidInputAndMissingCsrfAreRejected() throws Exception {
        TestSession alice = TestSession.login(port, "alice", PASSWORD);
        assertThat(alice.send("POST", "/api/boards/" + alphaBoard + "/elements",
                "{\"type\":\"TEXT\",\"x\":-5,\"y\":1,\"width\":100,\"height\":50}").statusCode()).isEqualTo(400);
        assertThat(alice.send("POST", "/api/boards/" + alphaBoard + "/elements",
                "{\"type\":\"TEXT\",\"x\":1,\"y\":1,\"width\":100,\"height\":50,\"content\":\"" + "x".repeat(70_000) + "\"}")
                .statusCode()).isEqualTo(400);
        long textElement = elements.findByBoardIdOrderById(alphaBoard).getFirst().getId();
        assertThat(alice.send("POST", "/api/boards/" + alphaBoard + "/elements/" + textElement + "/run", null).statusCode())
                .isEqualTo(400);

        TestSession noCsrf = TestSession.login(port, "alice", PASSWORD);
        noCsrf.sendCsrf = false;
        assertThat(noCsrf.send("POST", "/api/boards/" + alphaBoard + "/elements",
                "{\"type\":\"TEXT\",\"x\":1,\"y\":1,\"width\":100,\"height\":50}").statusCode()).isEqualTo(403);
    }
}
