package com.example.realtimedevboard.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real isolation tests: every test starts real Docker containers with the production limits.
 * Requires a running Docker daemon; fails (does not skip) when Docker is unavailable.
 */
class DockerSandboxIT {

    static final ExecutionProperties LIMITS = DockerJavaExecutorTest.LIMITS;
    static final DockerJavaExecutor executor = new DockerJavaExecutor(LIMITS);

    @BeforeAll
    static void dockerAndImageAvailable() throws Exception {
        assertThat(docker("version", "--format", "{{.Server.Version}}").exit)
                .as("Docker daemon must be running for isolation tests").isZero();
        if (docker("image", "inspect", LIMITS.image()).exit != 0) {
            assertThat(docker("pull", LIMITS.image()).exit).as("pull " + LIMITS.image()).isZero();
        }
    }

    @AfterEach
    void noJobContainerLeftBehind() throws Exception {
        assertThat(docker("ps", "-aq", "--filter", "label=" + DockerJavaExecutor.LABEL).out.strip())
                .as("job containers after test").isEmpty();
    }

    static ExecutionResult run(String mainBody) {
        return executor.execute("""
                import java.io.*; import java.net.*; import java.nio.file.*; import java.util.*;
                public class Main {
                    public static void main(String[] args) throws Exception {
                %s
                    }
                }
                """.formatted(mainBody));
    }

    // 1
    @Test
    void hello() {
        ExecutionResult r = executor.execute("""
                public class Main {
                    public static void main(String[] args) {
                        System.out.println("Hello");
                    }
                }
                """);
        assertThat(r.status()).as(r.toString()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(r.stdout()).isEqualTo("Hello\n");
        assertThat(r.exitCode()).isZero();
    }

    // 2
    @Test
    void syntaxError() {
        ExecutionResult r = executor.execute("public class Main { public static void main(String[] a) { int x = } }");
        assertThat(r.status()).isEqualTo(ExecutionStatus.COMPILATION_ERROR);
        assertThat(r.stderr()).contains("Main.java:1: error");
    }

    // 3
    @Test
    void runtimeException() {
        ExecutionResult r = run("System.out.println(1 / (args.length));");
        assertThat(r.status()).isEqualTo(ExecutionStatus.RUNTIME_ERROR);
        assertThat(r.stderr()).contains("java.lang.ArithmeticException");
        assertThat(r.exitCode()).isEqualTo(1);
    }

    // 4
    @Test
    void infiniteLoopTimesOut() {
        ExecutionResult r = run("System.out.println(\"start\"); while (true) { }");
        assertThat(r.status()).isEqualTo(ExecutionStatus.TIMEOUT);
        assertThat(r.stdout()).contains("start");
        // 7 s run limit + container start + compilation on 0.5 CPU + cleanup tolerance
        assertThat(r.durationMs()).isBetween(7_000L, 7_000L + 15_000L);
    }

    // 5a: heap accumulation stops at -Xmx (JVM-level limit)
    @Test
    void heapAccumulationIsJavaOutOfMemory() {
        ExecutionResult r = run("List<byte[]> keep = new ArrayList<>(); while (true) keep.add(new byte[1_000_000]);");
        assertThat(r.status()).as(r.toString()).isEqualTo(ExecutionStatus.MEMORY_LIMIT_EXCEEDED);
        assertThat(r.message()).contains("Java heap");
        assertThat(r.stderr()).contains("java.lang.OutOfMemoryError");
    }

    // 5b: a child JVM with its own -Xmx1g bypasses our -Xmx, the container cgroup limit still kills it
    @Test
    void childJvmBypassingXmxIsKilledByContainerLimit() {
        ExecutionResult r = run("""
                if (args.length > 0) {
                    List<byte[]> keep = new ArrayList<>();
                    while (true) { byte[] b = new byte[1_000_000]; Arrays.fill(b, (byte) 1); keep.add(b); }
                }
                Process child = new ProcessBuilder("java", "-Xmx1g", "-XX:-UsePerfData", "-cp", "/work", "Main", "child")
                        .inheritIO().start();
                System.out.println("child exit " + child.waitFor());
                """);
        assertThat(r.status()).as(r.toString()).isEqualTo(ExecutionStatus.MEMORY_LIMIT_EXCEEDED);
        assertThat(r.message()).contains("kernel OOM");
        assertThat(r.stdout()).contains("child exit 137");
    }

    // 6
    @Test
    void cpuQuotaLimitsFourBusyThreads() {
        ExecutionResult r = run("""
                System.out.println("cpu.max " + Files.readString(Path.of("/sys/fs/cgroup/cpu.max")).strip());
                var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                long cpu0 = os.getProcessCpuTime(), wall0 = System.nanoTime();
                List<Thread> threads = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    Thread t = new Thread(() -> { long end = System.nanoTime() + 3_000_000_000L; while (System.nanoTime() < end) { } });
                    t.start(); threads.add(t);
                }
                for (Thread t : threads) t.join();
                System.out.printf("ratio %.2f%n", (os.getProcessCpuTime() - cpu0) / (double) (System.nanoTime() - wall0));
                """);
        assertThat(r.status()).as(r.toString()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(r.stdout()).contains("cpu.max 50000 100000");
        double ratio = Double.parseDouble(r.stdout().replaceAll("(?s).*ratio ([0-9.]+).*", "$1"));
        // 4 unthrottled threads would give ~4.0; quota is 0.5 CPU, tolerance for scheduler accounting
        assertThat(ratio).isLessThan(0.75);
    }

    // 7
    @Test
    void fileSystemIsolation() throws Exception {
        Path hostSecret = Files.createTempFile("devboard-host-secret", ".txt");
        String token = "HOST-SECRET-" + System.nanoTime();
        Files.writeString(hostSecret, token);
        try {
            ExecutionResult r = run("""
                    Files.writeString(Path.of("/work/ok.txt"), "temp"); System.out.println("work write ok " + Files.readString(Path.of("/work/ok.txt")));
                    for (String p : new String[]{"/etc/devboard", "/opt/java/openjdk/evil", "/evil"}) {
                        try { Files.writeString(Path.of(p), "x"); System.out.println("WROTE " + p); }
                        catch (IOException e) { System.out.println("denied " + p); }
                    }
                    try { Files.write(Path.of("/work/big"), new byte[20 * 1024 * 1024]); System.out.println("WROTE big"); }
                    catch (IOException e) { System.out.println("denied big " + e.getMessage()); }
                    System.out.println("etc/os-release readable " + Files.exists(Path.of("/etc/os-release")));
                    try { System.out.println("HOST " + Files.readString(Path.of("%s"))); }
                    catch (IOException e) { System.out.println("host file not visible"); }
                    for (String line : Files.readAllLines(Path.of("/proc/self/mountinfo"))) System.out.println("mount " + line.split(" ")[4]);
                    """.formatted(hostSecret.toString().replace("\\", "\\\\")));
            assertThat(r.status()).as(r.toString()).isEqualTo(ExecutionStatus.SUCCESS);
            assertThat(r.stdout()).contains("work write ok temp", "denied /etc/devboard", "denied /opt/java/openjdk/evil",
                    "denied /evil", "denied big", "etc/os-release readable true", "host file not visible");
            assertThat(r.stdout()).doesNotContain("WROTE", token);
            // Only Docker's own mounts exist: no host directory is bind-mounted into the sandbox
            Set<String> mounts = Arrays.stream(r.stdout().split("\n")).filter(l -> l.startsWith("mount "))
                    .map(l -> l.substring(6)).collect(Collectors.toSet());
            assertThat(mounts).allSatisfy(m -> assertThat(m).matches(
                    "/|/work|/tmp|/proc.*|/dev.*|/sys.*|/etc/(hosts|hostname|resolv\\.conf)"));
        } finally {
            Files.delete(hostSecret);
        }
    }

    // 8
    @Test
    void noNetworkAccessToRunningServer() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))) {
            int port = server.getLocalPort();
            AtomicInteger accepted = new AtomicInteger();
            Thread acceptor = Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try (Socket ignored = server.accept()) { accepted.incrementAndGet(); } catch (IOException e) { return; }
                }
            });
            // control: the server really accepts connections
            try (Socket s = new Socket("127.0.0.1", port)) {
                assertThat(s.isConnected()).isTrue();
            }
            long deadline = System.currentTimeMillis() + 2000;
            while (accepted.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertThat(accepted.get()).as("control connection").isEqualTo(1);

            ExecutionResult r = run("""
                    for (String host : new String[]{"host.docker.internal", "172.17.0.1", "1.1.1.1"}) {
                        try (Socket s = new Socket()) { s.connect(new InetSocketAddress(host, %d), 2000); System.out.println("CONNECTED " + host); }
                        catch (IOException e) { System.out.println("blocked " + host + " " + e.getClass().getSimpleName()); }
                    }
                    var nics = Collections.list(NetworkInterface.getNetworkInterfaces()).stream().map(NetworkInterface::getName).sorted().toList();
                    System.out.println("interfaces " + nics);
                    """.formatted(port));
            assertThat(r.status()).as(r.toString()).isEqualTo(ExecutionStatus.SUCCESS);
            assertThat(r.stdout()).doesNotContain("CONNECTED").contains("blocked host.docker.internal", "blocked 172.17.0.1",
                    "interfaces [lo]");
            assertThat(accepted.get()).as("connections from sandbox").isEqualTo(1);
            server.close();
            acceptor.join(2000);
        }
    }

    // 9
    @Test
    void childProcessIsStoppedWithContainerOnTimeout() {
        ExecutionResult r = run("""
                new ProcessBuilder("sleep", "1000").start();
                System.out.println("child started"); System.out.flush();
                Thread.sleep(60_000);
                """);
        assertThat(r.status()).isEqualTo(ExecutionStatus.TIMEOUT);
        assertThat(r.stdout()).contains("child started");
        // @AfterEach asserts the container (and therefore the sleep child) no longer exists
    }

    // 9b
    @Test
    void threadAndProcessCountIsBounded() {
        ExecutionResult r = run("""
                int n = 0;
                try {
                    while (n < 10_000) { Thread t = new Thread(() -> { try { Thread.sleep(60_000); } catch (InterruptedException e) { } });
                        t.setDaemon(true); t.start(); n++; }
                } catch (Throwable e) { System.out.println("stopped by " + e.getClass().getSimpleName()); }
                System.out.println("threads " + n);
                """);
        assertThat(r.status()).as(r.toString()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(r.stdout()).contains("stopped by OutOfMemoryError");
        int threads = Integer.parseInt(r.stdout().replaceAll("(?s).*threads (\\d+).*", "$1"));
        assertThat(threads).isLessThan(LIMITS.pidsLimit());
    }

    // 10
    @Test
    void unlimitedOutputIsCut() {
        ExecutionResult r = run("while (true) System.out.println(\"spam spam spam spam spam spam\");");
        assertThat(r.status()).isEqualTo(ExecutionStatus.OUTPUT_LIMIT_EXCEEDED);
        assertThat(r.stdout().length() + r.stderr().length()).isLessThanOrEqualTo(LIMITS.outputLimitBytes());
        assertThat(r.durationMs()).isLessThan(7_000L + 15_000L);
    }

    // 11 (engine level; API health during execution is checked in ExecutionApiIT)
    @Test
    void helloWorksAfterLimitViolations() {
        assertThat(run("while (true) { }").status()).isEqualTo(ExecutionStatus.TIMEOUT);
        assertThat(run("System.out.print(\"again\");").stdout()).isEqualTo("again");
    }

    record Cmd(int exit, String out) {
    }

    static Cmd docker(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "docker";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(10, TimeUnit.MINUTES);
        return new Cmd(p.exitValue(), out);
    }
}
