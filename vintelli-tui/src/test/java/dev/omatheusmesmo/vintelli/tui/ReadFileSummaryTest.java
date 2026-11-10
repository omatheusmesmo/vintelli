package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;
import dev.omatheusmesmo.vintelli.api.capability.SkippedProvider;

class ReadFileSummaryTest {

    @Test
    void showsCheckingUntilTheFirstReadSettles() {
        ReadFileSummary readFile = new ReadFileSummary(FixedStatusRegistry.withStatuses(List.of()));

        assertEquals("Read File: checking provider…", readFile.title());
    }

    @Test
    void settlesToTheActiveProviderAndItsSkippedProviders() throws InterruptedException {
        ReadFileSummary readFile = new ReadFileSummary(FixedStatusRegistry.withStatuses(List.of(
                CapabilityStatus.available(CapabilityType.READ_FILE, "lsp", "lsp over stdio", List.of(
                        new SkippedProvider("filesystem", "not started"),
                        new SkippedProvider("backup", "not configured"))))));

        readFile.refresh();

        awaitTitle(readFile::title, "Read File: lsp (lsp over stdio)  skipped filesystem: not started"
                + "  ·  skipped backup: not configured");
    }

    @Test
    void settlesToTheReasonWhenTheProviderIsUnavailable() throws InterruptedException {
        ReadFileSummary readFile = new ReadFileSummary(FixedStatusRegistry.withStatuses(List.of(
                CapabilityStatus.unavailable(CapabilityType.READ_FILE, "no reader on the path"))));

        readFile.refresh();

        awaitTitle(readFile::title, "Read File unavailable: no reader on the path");
    }

    @Test
    void withoutARegistryTheFooterSaysItIsNotConfigured() {
        ReadFileSummary readFile = new ReadFileSummary(null);

        readFile.refresh();

        assertEquals("Read File unavailable: capability registry is not configured", readFile.title());
    }

    private static void awaitTitle(Supplier<String> title, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!expected.equals(title.get()) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, title.get());
    }
}
