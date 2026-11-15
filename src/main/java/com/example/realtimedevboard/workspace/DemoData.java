package com.example.realtimedevboard.workspace;

import com.example.realtimedevboard.board.Board;
import com.example.realtimedevboard.board.BoardElement;
import com.example.realtimedevboard.board.BoardElementRepository;
import com.example.realtimedevboard.board.BoardRepository;
import com.example.realtimedevboard.board.ElementType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Seeds two workspaces for the tenant-isolation demo, only into an empty database
 * (never overwrites existing data). The password comes from DEMO_PASSWORD, not from the repository.
 */
@Component
public class DemoData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoData.class);
    static final String HELLO = """
            public class Main {
                public static void main(String[] args) {
                    System.out.println("Hello");
                }
            }
            """;

    private final AppUserRepository users;
    private final WorkspaceRepository workspaces;
    private final BoardRepository boards;
    private final BoardElementRepository elements;
    private final PasswordEncoder encoder;
    private final String demoPassword;

    public DemoData(AppUserRepository users, WorkspaceRepository workspaces, BoardRepository boards,
                    BoardElementRepository elements, PasswordEncoder encoder,
                    @Value("${devboard.demo-password:}") String demoPassword) {
        this.users = users;
        this.workspaces = workspaces;
        this.boards = boards;
        this.elements = elements;
        this.encoder = encoder;
        this.demoPassword = demoPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        if (demoPassword.isBlank()) {
            log.warn("DEMO_PASSWORD is not set: demo accounts were not created");
            return;
        }
        String hash = encoder.encode(demoPassword);
        AppUser alice = users.save(new AppUser("alice", hash));
        AppUser adam = users.save(new AppUser("adam", hash));
        AppUser bob = users.save(new AppUser("bob", hash));

        Workspace alpha = workspaces.save(new Workspace("Team Alpha", Set.of(alice, adam)));
        Workspace beta = workspaces.save(new Workspace("Team Beta", Set.of(bob)));

        Board alphaBoard = boards.save(new Board(alpha, "Alpha board"));
        elements.save(new BoardElement(alphaBoard, ElementType.TEXT, 40, 40, 260, 90, "Paspausk Run ant Java bloko"));
        elements.save(new BoardElement(alphaBoard, ElementType.CODE, 40, 160, 460, 300, HELLO));
        Board betaBoard = boards.save(new Board(beta, "Beta secret board"));
        elements.save(new BoardElement(betaBoard, ElementType.TEXT, 40, 40, 260, 90, "Beta only"));
        log.info("Demo data created: alice, adam (Team Alpha), bob (Team Beta)");
    }
}
