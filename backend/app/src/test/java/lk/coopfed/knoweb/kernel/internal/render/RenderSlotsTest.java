package lk.coopfed.knoweb.kernel.internal.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.junit.jupiter.api.Test;

/**
 * Wave 2, TWK-28 (decided 6 October 2026: docs/progress/deviations/2026-10-06-wave2-kernel-defaults-and-limits.md
 * (5)): at most {@code render.max_concurrent} browsers at once, a render that cannot start within
 * its bound answered {@code render.busy}; and the compensating control for Chromium's
 * {@code --no-sandbox}, a template may not write unescaped text.
 */
class RenderSlotsTest {

    @Test
    void aRenderWaitsForAFreeSlotAndIsAnsweredBusyPastItsBound() throws Exception {
        RenderSlots slots = new RenderSlots();
        CountDownLatch inside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> running = List.of(
                    pool.submit(() -> slots.run(2, Duration.ofSeconds(5), () -> hold(inside, release))),
                    pool.submit(() -> slots.run(2, Duration.ofSeconds(5), () -> hold(inside, release))));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(slots.running()).isEqualTo(2);

            assertThatThrownBy(() -> slots.run(2, Duration.ofMillis(100), () -> "third"))
                    .isInstanceOfSatisfying(ProblemException.class, busy -> assertThat(busy.messageId())
                            .isEqualTo("render.busy"));

            release.countDown();
            for (Future<String> render : running) {
                assertThat(render.get(5, TimeUnit.SECONDS)).isEqualTo("printed");
            }
            assertThat(slots.running()).isZero();
            assertThat(slots.run(2, Duration.ofMillis(100), () -> "next")).isEqualTo("next");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aFailedRenderGivesItsSlotBack() {
        RenderSlots slots = new RenderSlots();
        assertThatThrownBy(() -> slots.run(1, Duration.ofMillis(100), () -> {
                    throw new IllegalStateException("chromium crashed");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(slots.running()).isZero();
    }

    /** No th:utext and no [( ... )] inlining in any print template: every value is escaped. */
    @Test
    void noTemplateWritesUnescapedText() throws IOException {
        // Relative to backend/app, the working directory of the Gradle test task.
        Path templates = Path.of("src/main/resources/reports/templates");
        assertThat(templates).isDirectory();
        try (Stream<Path> files = Files.walk(templates)) {
            List<String> unescaped = files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".html"))
                    .filter(file -> {
                        try {
                            String text = Files.readString(file);
                            return text.contains("th:utext") || text.contains("[(");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(Path::toString)
                    .toList();
            assertThat(unescaped).as("templates that write unescaped text").isEmpty();
        }
    }

    private static String hold(CountDownLatch inside, CountDownLatch release) {
        inside.countDown();
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "printed";
    }
}
