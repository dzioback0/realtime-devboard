package com.example.realtimedevboard.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Compiles and runs one Java program in its own short-lived Docker container.
 * <p>
 * The container's main process is only {@code sleep}; javac and java are started with {@code docker exec},
 * so the two phases get separate time limits. Any limit violation ends with {@code docker rm -f}, which
 * kills every process in the container (killing just the docker CLI process would leave them running).
 * {@code --rm} plus the finite sleep is a safety net if this JVM dies before cleanup.
 */
@Component
public class DockerJavaExecutor {

    static final String LABEL = "devboard.job";
    private static final Logger log = LoggerFactory.getLogger(DockerJavaExecutor.class);
    private static final Duration DOCKER_CALL_TIMEOUT = Duration.ofSeconds(30);

    private final ExecutionProperties limits;

    public DockerJavaExecutor(ExecutionProperties limits) {
        this.limits = limits;
    }

    public ExecutionResult execute(String code) {
        String name = "devboard-job-" + UUID.randomUUID();
        long started = System.nanoTime();
        try {
            Step create = runStep(containerCommand(name), null, DOCKER_CALL_TIMEOUT, null);
            if (create.exitCode() != 0) {
                return infrastructureError("Sandbox container could not be started: " + create.stderr().strip(), started);
            }
            Step write = runStep(exec(name, "sh", "-c", "cat > /work/Main.java"), code.getBytes(UTF_8), DOCKER_CALL_TIMEOUT, null);
            if (write.exitCode() != 0) {
                return infrastructureError("Source could not be copied into the sandbox: " + write.stderr().strip(), started);
            }

            Step compile = runStep(exec(name, "javac", "-J-Xmx" + limits.javaHeap(), "-J-XX:-UsePerfData",
                    "-d", "/work", "/work/Main.java"), null, limits.compileTimeout(), name);
            ExecutionResult compileFailure = classifyCompile(compile, oomKillsIfAlive(name, compile), millisSince(started));
            if (compileFailure != null) {
                return compileFailure;
            }

            Step run = runStep(exec(name, "java", "-Xmx" + limits.javaHeap(), "-XX:-UsePerfData",
                    "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-cp", "/work", "Main"), null, limits.runTimeout(), name);
            return classifyRun(run, oomKillsIfAlive(name, run), millisSince(started));
        } catch (IOException e) {
            return infrastructureError("Docker is not available: " + e.getMessage(), started);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return infrastructureError("Execution was cancelled", started);
        } finally {
            removeContainer(name);
        }
    }

    List<String> containerCommand(String name) {
        long lifetimeSeconds = limits.compileTimeout().plus(limits.runTimeout()).toSeconds() + 30;
        String tmpfsOptions = ":rw,noexec,nosuid,nodev,size=" + limits.workdirSize() + ",mode=1777";
        return List.of("docker", "run", "-d", "--rm", "--name", name, "--label", LABEL,
                "--network", "none",
                "--memory", limits.memory(), "--memory-swap", limits.memory(), // equal values = no swap
                "--cpus", limits.cpus(),
                "--pids-limit", String.valueOf(limits.pidsLimit()),
                "--read-only",
                "--tmpfs", "/work" + tmpfsOptions,
                "--tmpfs", "/tmp" + tmpfsOptions,
                "--workdir", "/work",
                "--env", "HOME=/tmp",
                "--user", "65534:65534",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                limits.image(), "sleep", String.valueOf(lifetimeSeconds));
    }

    ExecutionResult classifyCompile(Step s, long oomKills, long ms) {
        if (s.timedOut()) {
            return result(ExecutionStatus.TIMEOUT, s, ms,
                    "Compilation exceeded " + limits.compileTimeout().toSeconds() + " s; sandbox container stopped");
        }
        if (s.outputExceeded()) {
            return result(ExecutionStatus.OUTPUT_LIMIT_EXCEEDED, s, ms, "Compiler output exceeded " + limits.outputLimitBytes() + " bytes");
        }
        if (oomKills > 0) {
            return result(ExecutionStatus.MEMORY_LIMIT_EXCEEDED, s, ms, "Compiler killed by the container memory limit (" + limits.memory() + ")");
        }
        if (s.exitCode() == 0) {
            return null;
        }
        if (s.exitCode() == 1 && s.stderr().contains("Main.java:")) {
            return result(ExecutionStatus.COMPILATION_ERROR, s, ms, "Compilation failed");
        }
        return result(ExecutionStatus.EXECUTION_ERROR, s, ms, "javac failed unexpectedly (exit code " + s.exitCode() + ")");
    }

    ExecutionResult classifyRun(Step s, long oomKills, long ms) {
        if (s.timedOut()) {
            return result(ExecutionStatus.TIMEOUT, s, ms,
                    "Program exceeded " + limits.runTimeout().toSeconds() + " s; container and all its processes were stopped");
        }
        if (s.outputExceeded()) {
            return result(ExecutionStatus.OUTPUT_LIMIT_EXCEEDED, s, ms,
                    "Output exceeded " + limits.outputLimitBytes() + " bytes; container and all its processes were stopped");
        }
        // Evidence comes from the kernel cgroup counter, not from exit code 137 (a plain SIGKILL looks the same).
        if (oomKills > 0) {
            return result(ExecutionStatus.MEMORY_LIMIT_EXCEEDED, s, ms,
                    "Container memory limit (" + limits.memory() + ") exceeded: kernel OOM killer stopped a process");
        }
        if (s.exitCode() == 1 && s.stderr().contains("java.lang.OutOfMemoryError")) {
            return result(ExecutionStatus.MEMORY_LIMIT_EXCEEDED, s, ms,
                    "Java heap limit (-Xmx" + limits.javaHeap() + ") exceeded: uncaught OutOfMemoryError");
        }
        if (s.exitCode() == 0) {
            return result(ExecutionStatus.SUCCESS, s, ms, "Program finished");
        }
        return result(ExecutionStatus.RUNTIME_ERROR, s, ms, "Program exited with code " + s.exitCode());
    }

    static long parseOomKills(String memoryEvents) {
        for (String line : memoryEvents.split("\n")) {
            if (line.startsWith("oom_kill ")) {
                return Long.parseLong(line.substring("oom_kill ".length()).strip());
            }
        }
        return 0;
    }

    /** Reads the container's cgroup v2 memory.events counter (written by the kernel, not by user code). */
    private long oomKillsIfAlive(String name, Step step) throws IOException, InterruptedException {
        if (step.timedOut() || step.outputExceeded()) {
            return 0; // container was already removed; status is decided by the limit that fired
        }
        Step events = runStep(exec(name, "cat", "/sys/fs/cgroup/memory.events"), null, DOCKER_CALL_TIMEOUT, null);
        if (events.exitCode() != 0) {
            log.warn("cgroup v2 memory.events not readable in {}; OOM kills cannot be detected: {}", name, events.stderr());
            return 0;
        }
        return parseOomKills(events.stdout());
    }

    private Step runStep(List<String> command, byte[] stdin, Duration timeout, String containerToKill)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).start();
        try (OutputStream in = process.getOutputStream()) {
            if (stdin != null) {
                in.write(stdin);
            }
        }
        AtomicInteger budget = new AtomicInteger(limits.outputLimitBytes()); // shared by stdout + stderr
        AtomicBoolean exceeded = new AtomicBoolean();
        Runnable onExceeded = () -> {
            if (exceeded.compareAndSet(false, true)) {
                kill(containerToKill, process);
            }
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        // Both streams are drained concurrently, so a full stderr pipe can never block the program.
        Thread outReader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), out, budget, onExceeded));
        Thread errReader = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), err, budget, onExceeded));

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            kill(containerToKill, process);
            process.waitFor(10, TimeUnit.SECONDS);
        }
        outReader.join(Duration.ofSeconds(5));
        errReader.join(Duration.ofSeconds(5));
        boolean killed = !finished || exceeded.get();
        return new Step(killed ? -1 : process.exitValue(), text(out), text(err), !finished, exceeded.get());
    }

    private static void drain(InputStream stream, ByteArrayOutputStream sink, AtomicInteger budget, Runnable onExceeded) {
        byte[] buffer = new byte[8192];
        try (stream) {
            int n;
            while ((n = stream.read(buffer)) != -1) {
                int allowed = Math.min(n, Math.max(0, budget.getAndAdd(-n)));
                synchronized (sink) {
                    sink.write(buffer, 0, allowed);
                }
                if (allowed < n) {
                    onExceeded.run(); // keep reading (and discarding) until the stream closes
                }
            }
        } catch (IOException ignored) {
            // stream closed because the process was killed
        }
    }

    private void kill(String containerName, Process process) {
        if (containerName != null) {
            removeContainer(containerName);
        }
        process.destroyForcibly();
    }

    private void removeContainer(String name) {
        try {
            Process rm = new ProcessBuilder("docker", "rm", "-f", name).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!rm.waitFor(DOCKER_CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                rm.destroyForcibly();
                log.error("docker rm -f {} timed out", name);
            }
        } catch (IOException e) {
            log.debug("docker rm -f {} failed: {}", name, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> exec(String container, String... command) {
        List<String> full = new ArrayList<>(List.of("docker", "exec", "-i", container));
        full.addAll(List.of(command));
        return full;
    }

    private static ExecutionResult result(ExecutionStatus status, Step s, long ms, String message) {
        return new ExecutionResult(status, s.stdout(), s.stderr(), s.exitCode() < 0 ? null : s.exitCode(), ms, message);
    }

    private static ExecutionResult infrastructureError(String message, long started) {
        return new ExecutionResult(ExecutionStatus.EXECUTION_ERROR, "", "", null, millisSince(started), message);
    }

    private static long millisSince(long nanoStart) {
        return (System.nanoTime() - nanoStart) / 1_000_000;
    }

    private static String text(ByteArrayOutputStream stream) {
        synchronized (stream) {
            return stream.toString(UTF_8);
        }
    }

    /** Outcome of one docker CLI call; exitCode is -1 when we killed it. */
    record Step(int exitCode, String stdout, String stderr, boolean timedOut, boolean outputExceeded) {
    }
}
