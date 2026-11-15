package com.example.realtimedevboard.execution;

/**
 * @param exitCode exit code of the last executed step (javac or java), null if it never finished
 * @param message  human readable explanation of the status (which limit, which phase)
 */
public record ExecutionResult(ExecutionStatus status, String stdout, String stderr,
                              Integer exitCode, long durationMs, String message) {

    static ExecutionResult of(ExecutionStatus status, String message) {
        return new ExecutionResult(status, "", "", null, 0, message);
    }
}
