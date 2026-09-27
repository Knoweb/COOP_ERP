package lk.coopfed.knoweb.kernel.internal.render;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prints one HTML page to PDF with the Chromium command line ({@code --headless
 * --print-to-pdf}), not through Playwright (K-06b; the choice and its reasons are in the module
 * README and in PROGRESS, Deviations): the page is written to a directory of its own with a
 * browser profile of its own, the process is killed when it outlives the timeout, and a PDF
 * over the limit is refused before it is read.
 *
 * <p>The page is self-contained: the fonts are data URIs and a content security policy forbids
 * anything else, so the browser never touches the network or the disk outside that directory.
 */
public final class ChromiumPdf {

    private static final Logger log = LoggerFactory.getLogger(ChromiumPdf.class);

    private final String binary;

    public ChromiumPdf(String binary) {
        this.binary = binary;
    }

    public String binary() {
        return binary;
    }

    /**
     * @param html     a complete page
     * @param timeout  the process is killed after this ({@code report.timeout})
     * @param maxBytes a larger PDF is refused ({@code report.too_large})
     */
    public byte[] print(String html, Duration timeout, long maxBytes) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("coop-report-");
            Path page = dir.resolve("page.html");
            Path pdf = dir.resolve("page.pdf");
            Files.writeString(page, html, StandardCharsets.UTF_8);
            List<String> command = List.of(
                    binary,
                    "--headless",
                    // The worker runs as an unprivileged user in a container, where Chromium's
                    // sandbox needs kernel features a container does not grant. What it opens is
                    // our own page, with a policy that loads nothing from outside it.
                    "--no-sandbox",
                    "--disable-gpu",
                    "--disable-dev-shm-usage",
                    "--disable-extensions",
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--hide-scrollbars",
                    "--mute-audio",
                    "--user-data-dir=" + dir.resolve("profile"),
                    "--no-pdf-header-footer",
                    // No --virtual-time-budget: it never ended on the pipeline's Linux Chrome. The
                    // fonts are data URIs, loaded before the load event the print waits for.
                    "--disable-crash-reporter",
                    "--enable-logging=stderr",
                    "--disable-breakpad",
                    "--print-to-pdf=" + pdf.toAbsolutePath(),
                    page.toUri().toString());
            ProcessBuilder builder = new ProcessBuilder(command)
                    // Nothing on standard input: a browser left holding the caller's input pipe
                    // was seen never to exit on the pipeline's runner.
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File(
                            System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null")))
                    .redirectErrorStream(true)
                    .redirectOutput(dir.resolve("chromium.log").toFile());
            builder.environment().putAll(Map.of("HOME", dir.toString()));
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                log.error("Chromium did not finish within {}: {}", timeout, tail(dir.resolve("chromium.log")));
                throw new ProblemException("report.timeout", Map.of("seconds", timeout.toSeconds()));
            }
            if (process.exitValue() != 0 || !Files.exists(pdf)) {
                log.error(
                        "Chromium exited with {} and no PDF: {}",
                        process.exitValue(),
                        tail(dir.resolve("chromium.log")));
                throw new ProblemException("report.failed");
            }
            long size = Files.size(pdf);
            if (size > maxBytes) {
                throw new ProblemException("report.too_large", Map.of("size", size, "maxBytes", maxBytes));
            }
            return Files.readAllBytes(pdf);
        } catch (IOException e) {
            log.error("Chromium could not be run ({}): {}", binary, e.getMessage());
            throw new ProblemException("report.failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProblemException("report.failed");
        } finally {
            if (dir != null) {
                delete(dir);
            }
        }
    }

    private static String tail(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return text.length() > 2000 ? text.substring(text.length() - 2000) : text;
        } catch (IOException | UncheckedIOException e) {
            return "(no log)";
        }
    }

    private static void delete(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // A browser that was killed may still hold a file on Windows; the temp
                    // directory is the operating system's to clean then.
                }
            });
        } catch (IOException e) {
            log.debug("Could not remove {}: {}", dir, e.getMessage());
        }
    }
}
