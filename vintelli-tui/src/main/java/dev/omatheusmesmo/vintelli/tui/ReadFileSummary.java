package dev.omatheusmesmo.vintelli.tui;

import java.util.concurrent.atomic.AtomicLong;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityRegistry;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityType;

/**
 * The READ_FILE state under the project list. {@link #refresh()} reads the registry off the render thread and
 * keeps only the newest read; {@link #title()} formats the stored result for rendering.
 */
final class ReadFileSummary {

    private final CapabilityRegistry registry;
    private final AtomicLong sequence = new AtomicLong();
    private volatile CapabilityStateLine.Summary summary;

    ReadFileSummary(CapabilityRegistry registry) {
        this.registry = registry;
        this.summary = registry == null
                ? CapabilityStateLine.notConfigured(CapabilityType.READ_FILE)
                : CapabilityStateLine.checking(CapabilityType.READ_FILE);
    }

    String title() {
        CapabilityStateLine.Summary current = summary;
        String skipped = current.skipped().isEmpty() ? "" : "  " + String.join("  ·  ", current.skipped());
        return current.headline() + skipped;
    }

    void refresh() {
        if (registry == null) {
            return;
        }
        long current = sequence.incrementAndGet();
        Thread.ofVirtual().name("vintelli-read-file-state").start(() -> {
            CapabilityStateLine.Summary read = CapabilityStateLine.read(registry, CapabilityType.READ_FILE);
            if (current == sequence.get()) {
                summary = read;
            }
        });
    }
}
