package com.example.realtimedevboard.execution;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExecutionServiceTest {

    private final DockerJavaExecutor executor = mock(DockerJavaExecutor.class);
    private final ExecutionService service = new ExecutionService(executor, DockerJavaExecutorTest.LIMITS);

    @Test
    void unsupportedLanguageIsNeverExecuted() {
        assertThat(service.execute("python", "print('x')").status()).isEqualTo(ExecutionStatus.UNSUPPORTED_LANGUAGE);
        verifyNoInteractions(executor);
    }

    @Test
    void oversizedCodeIsRejectedWith413() {
        assertThatThrownBy(() -> service.execute("java", "x".repeat(65537)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
        verifyNoInteractions(executor);
    }

    @Test
    void concurrentExecutionsAreLimited() throws Exception {
        CountDownLatch running = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(executor.execute(anyString())).thenAnswer(inv -> {
            running.countDown();
            release.await();
            return ExecutionResult.of(ExecutionStatus.SUCCESS, "ok");
        });
        Thread a = Thread.ofVirtual().start(() -> service.execute("java", "a"));
        Thread b = Thread.ofVirtual().start(() -> service.execute("java", "b"));
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.execute("java", "c"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        release.countDown();
        a.join();
        b.join();
        when(executor.execute(anyString())).thenReturn(ExecutionResult.of(ExecutionStatus.SUCCESS, "ok"));
        assertThat(service.execute("java", "d").status()).isEqualTo(ExecutionStatus.SUCCESS);
    }
}
