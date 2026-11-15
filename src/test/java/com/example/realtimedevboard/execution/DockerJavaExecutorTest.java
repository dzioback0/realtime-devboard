package com.example.realtimedevboard.execution;

import com.example.realtimedevboard.execution.DockerJavaExecutor.Step;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Decision logic only. Real isolation is verified by DockerSandboxIT against Docker. */
class DockerJavaExecutorTest {

    static final ExecutionProperties LIMITS = new ExecutionProperties("eclipse-temurin:21-jdk",
            Duration.ofSeconds(7), Duration.ofSeconds(15), "256m", "128m", "0.5", 128, "16m", 65536, 65536, 2);

    private final DockerJavaExecutor executor = new DockerJavaExecutor(LIMITS);

    private static Step finished(int exit, String stdout, String stderr) {
        return new Step(exit, stdout, stderr, false, false);
    }

    @Test
    void successfulRun() {
        ExecutionResult r = executor.classifyRun(finished(0, "Hello\n", ""), 0, 10);
        assertThat(r.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(r.stdout()).isEqualTo("Hello\n");
    }

    @Test
    void timeoutWinsOverEverythingElse() {
        assertThat(executor.classifyRun(new Step(-1, "", "", true, false), 0, 7000).status())
                .isEqualTo(ExecutionStatus.TIMEOUT);
    }

    @Test
    void outputLimit() {
        assertThat(executor.classifyRun(new Step(-1, "x", "", false, true), 0, 10).status())
                .isEqualTo(ExecutionStatus.OUTPUT_LIMIT_EXCEEDED);
    }

    @Test
    void exitCode137WithoutKernelEvidenceIsNotMemoryLimit() {
        ExecutionResult r = executor.classifyRun(finished(137, "", ""), 0, 10);
        assertThat(r.status()).isEqualTo(ExecutionStatus.RUNTIME_ERROR);
    }

    @Test
    void kernelOomKillIsMemoryLimitEvenIfProgramExitedNormally() {
        ExecutionResult r = executor.classifyRun(finished(0, "child exit 137", ""), 1, 10);
        assertThat(r.status()).isEqualTo(ExecutionStatus.MEMORY_LIMIT_EXCEEDED);
        assertThat(r.message()).contains("kernel OOM");
    }

    @Test
    void javaHeapOutOfMemoryIsMemoryLimitWithDifferentMessage() {
        ExecutionResult r = executor.classifyRun(finished(1, "",
                "Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap space"), 0, 10);
        assertThat(r.status()).isEqualTo(ExecutionStatus.MEMORY_LIMIT_EXCEEDED);
        assertThat(r.message()).contains("Java heap");
    }

    @Test
    void runtimeException() {
        assertThat(executor.classifyRun(finished(1, "", "java.lang.ArithmeticException"), 0, 10).status())
                .isEqualTo(ExecutionStatus.RUNTIME_ERROR);
    }

    @Test
    void compilation() {
        assertThat(executor.classifyCompile(finished(0, "", ""), 0, 10)).isNull();
        assertThat(executor.classifyCompile(finished(1, "", "/work/Main.java:3: error: ';' expected"), 0, 10).status())
                .isEqualTo(ExecutionStatus.COMPILATION_ERROR);
        assertThat(executor.classifyCompile(new Step(-1, "", "", true, false), 0, 15000).message())
                .contains("Compilation exceeded 15 s");
        assertThat(executor.classifyCompile(finished(125, "", "docker: error"), 0, 10).status())
                .isEqualTo(ExecutionStatus.EXECUTION_ERROR);
    }

    @Test
    void parsesCgroupMemoryEvents() {
        assertThat(DockerJavaExecutor.parseOomKills("low 0\nhigh 0\nmax 12\noom 1\noom_kill 1\n")).isEqualTo(1);
        assertThat(DockerJavaExecutor.parseOomKills("low 0\n")).isZero();
    }

    @Test
    void containerIsLockedDown() {
        List<String> cmd = executor.containerCommand("job");
        assertThat(String.join(" ", cmd)).contains(
                "--network none", "--memory 256m", "--memory-swap 256m", "--cpus 0.5", "--pids-limit 128",
                "--read-only", "--user 65534:65534", "--cap-drop ALL", "--security-opt no-new-privileges", "--rm");
        assertThat(cmd).doesNotContain("-v", "--volume", "--mount", "--privileged", "--pid", "--ipc");
        assertThat(String.join(" ", cmd)).doesNotContain("docker.sock", "host");
    }
}
