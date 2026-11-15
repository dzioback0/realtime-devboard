package com.example.realtimedevboard.execution;

public enum ExecutionStatus {
    SUCCESS,
    COMPILATION_ERROR,
    RUNTIME_ERROR,
    TIMEOUT,
    MEMORY_LIMIT_EXCEEDED,
    OUTPUT_LIMIT_EXCEEDED,
    UNSUPPORTED_LANGUAGE,
    /** Sandbox infrastructure failed (Docker missing, container could not start...). Never reported as success. */
    EXECUTION_ERROR
}
