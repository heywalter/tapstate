package io.tapstate.core.logging;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {

    @Test
    void replacesRegisteredValuesAndTracksOwnerLifecycle() {
        SecretRedactor redactor = new SecretRedactor();
        redactor.replace("source-a", List.of("sentinel", "sentinel-long"));
        redactor.replace("source-b", List.of("second-secret"));

        assertThat(redactor.redact("sentinel-long / sentinel / second-secret"))
                .isEqualTo("******** / ******** / ********");

        redactor.replace("source-a", List.of("replacement"));
        redactor.remove("source-b");

        assertThat(redactor.redact("sentinel second-secret replacement"))
                .isEqualTo("sentinel second-secret ********");
    }

    @Test
    void ignoresEmptyValuesAndLeavesNullTextNull() {
        SecretRedactor redactor = new SecretRedactor();
        redactor.replace("source", List.of("", "  "));

        assertThat(redactor.redact("ordinary line")).isEqualTo("ordinary line");
        assertThat(redactor.redact(null)).isNull();
    }

    @Test
    void publishesCompleteSnapshotsToConcurrentReaders() throws Exception {
        SecretRedactor redactor = new SecretRedactor();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Future<?>> tasks = new ArrayList<>();
            tasks.add(executor.submit(() -> {
                await(start);
                for (int iteration = 0; iteration < 2_000; iteration++) {
                    redactor.replace("source", List.of(iteration % 2 == 0 ? "alpha" : "beta"));
                    if (iteration % 3 == 0) {
                        redactor.remove("source");
                    }
                }
            }));
            for (int reader = 0; reader < 3; reader++) {
                tasks.add(executor.submit(() -> {
                    await(start);
                    for (int iteration = 0; iteration < 2_000; iteration++) {
                        assertThat(redactor.redact("alpha beta"))
                                .isIn("******** beta", "alpha ********", "alpha beta");
                    }
                }));
            }

            start.countDown();
            for (Future<?> task : tasks) {
                task.get();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
