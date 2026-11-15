package com.example.realtimedevboard.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Sandbox limits; defaults live in application.properties (devboard.execution.*). */
@ConfigurationProperties("devboard.execution")
public record ExecutionProperties(
        String image,
        Duration runTimeout,
        Duration compileTimeout,
        String memory,
        String javaHeap,
        String cpus,
        int pidsLimit,
        String workdirSize,
        int outputLimitBytes,
        int maxCodeBytes,
        int maxConcurrent) {
}
