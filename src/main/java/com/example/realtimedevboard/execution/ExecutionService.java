package com.example.realtimedevboard.execution;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.Semaphore;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Entry point of the Code Execution Engine: language check, request size and concurrency limits. */
@Service
public class ExecutionService {

    private final DockerJavaExecutor javaExecutor;
    private final ExecutionProperties limits;
    private final Semaphore slots;

    public ExecutionService(DockerJavaExecutor javaExecutor, ExecutionProperties limits) {
        this.javaExecutor = javaExecutor;
        this.limits = limits;
        this.slots = new Semaphore(limits.maxConcurrent());
    }

    public ExecutionResult execute(String language, String code) {
        if (!"java".equalsIgnoreCase(language)) {
            return ExecutionResult.of(ExecutionStatus.UNSUPPORTED_LANGUAGE, "Only Java is supported, got: " + language);
        }
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Code is empty");
        }
        if (code.getBytes(UTF_8).length > limits.maxCodeBytes()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Code exceeds " + limits.maxCodeBytes() + " bytes");
        }
        if (!slots.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many programs running, try again shortly");
        }
        try {
            return javaExecutor.execute(code);
        } finally {
            slots.release();
        }
    }
}
