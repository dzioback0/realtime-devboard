package com.example.realtimedevboard.board;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Upgrades databases created by earlier versions; idempotent, runs on every start.
 * shortcut: hand-written SQL instead of Flyway, upgrade to Flyway once there is a second schema change.
 */
@Component
@Order(0)
class SchemaUpgrade implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaUpgrade.class);
    private final JdbcTemplate jdbc;

    SchemaUpgrade(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        // ddl-auto=update never widens Hibernate's enum CHECK constraint, so ARROW would be rejected.
        // Element types stay validated by the ElementType enum mapping in the application.
        jdbc.execute("ALTER TABLE board_element DROP CONSTRAINT IF EXISTS board_element_type_check");
        // Shapes used to keep their kind in content; content is now the label inside the shape.
        int moved = jdbc.update("UPDATE board_element SET shape = content, content = '' "
                + "WHERE type = 'SHAPE' AND shape IS NULL AND content IN ('RECT', 'CIRCLE')");
        if (moved > 0) {
            log.info("Moved shape kind out of content for {} elements", moved);
        }
    }
}
