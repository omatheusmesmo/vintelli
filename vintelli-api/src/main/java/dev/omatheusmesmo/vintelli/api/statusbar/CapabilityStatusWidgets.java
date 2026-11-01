package dev.omatheusmesmo.vintelli.api.statusbar;

import java.util.List;
import java.util.Objects;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityStatus;

/** Selected provider status and its optional status bar additions. */
public record CapabilityStatusWidgets(CapabilityStatus status, List<StatusBarWidget> widgets) {

    public CapabilityStatusWidgets {
        Objects.requireNonNull(status, "status");
        widgets = widgets == null ? List.of() : List.copyOf(widgets);
        if (!status.isAvailable() && !widgets.isEmpty()) {
            throw new IllegalArgumentException("Unavailable providers cannot contribute status bar widgets");
        }
    }
}
