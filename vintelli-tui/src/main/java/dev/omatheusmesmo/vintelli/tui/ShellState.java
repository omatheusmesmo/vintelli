package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.ShellStateSnapshot;
import dev.omatheusmesmo.vintelli.api.ShellStateView;
import io.quarkus.arc.Unremovable;
import jakarta.inject.Singleton;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The writable holder behind {@link ShellStateView}: the shell and the tool
 * windows update the selection here, every other surface reads snapshots
 * from it. A single atomic reference replaces the whole snapshot at once, so
 * readers never see the tool window id and the file from different moments.
 */
@Unremovable
@Singleton
public final class ShellState implements ShellStateView {

    private final AtomicReference<ShellStateSnapshot> snapshot =
            new AtomicReference<>(new ShellStateSnapshot("", ""));

    @Override
    public ShellStateSnapshot snapshot() {
        return snapshot.get();
    }

    void selectToolWindow(String toolWindowId) {
        update(toolWindowId, snapshot.get().selectedFile());
    }

    void selectFile(String file) {
        update(snapshot.get().selectedToolWindowId(), file);
    }

    private void update(String toolWindowId, String file) {
        Objects.requireNonNull(toolWindowId, "toolWindowId");
        Objects.requireNonNull(file, "file");
        snapshot.set(new ShellStateSnapshot(toolWindowId, file));
    }
}
