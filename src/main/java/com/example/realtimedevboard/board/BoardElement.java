package com.example.realtimedevboard.board;

import com.example.realtimedevboard.execution.ExecutionResult;
import com.example.realtimedevboard.execution.ExecutionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/** A board item. A CODE element also keeps its last execution result, so the console survives a page reload. */
@Entity
public class BoardElement {

    static final int MAX_CONTENT = 65_536;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Board board;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ElementType type;

    private int x;
    private int y;
    private int width;
    private int height;

    @Column(length = MAX_CONTENT)
    private String content;

    @Enumerated(EnumType.STRING)
    private ExecutionStatus runStatus;
    @Column(length = MAX_CONTENT)
    private String runStdout;
    @Column(length = MAX_CONTENT)
    private String runStderr;
    @Column(length = 1000)
    private String runMessage;
    private Integer runExitCode;
    private Long runDurationMs;

    protected BoardElement() {
    }

    public BoardElement(Board board, ElementType type, int x, int y, int width, int height, String content) {
        this.board = board;
        this.type = type;
        update(x, y, width, height, content);
    }

    void update(int x, int y, int width, int height, String content) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.content = content;
    }

    void recordRun(ExecutionResult r) {
        runStatus = r.status();
        runStdout = r.stdout();
        runStderr = r.stderr();
        runMessage = r.message();
        runExitCode = r.exitCode();
        runDurationMs = r.durationMs();
    }

    public Long getId() {
        return id;
    }

    public ElementType getType() {
        return type;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public String getContent() {
        return content;
    }

    /** Null until the element has been run. */
    public ExecutionResult getLastRun() {
        return runStatus == null ? null
                : new ExecutionResult(runStatus, runStdout, runStderr, runExitCode, runDurationMs, runMessage);
    }
}
