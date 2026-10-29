package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.ShellStateSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellStateTest {

    @Test
    void startsWithNothingSelected() {
        ShellState state = new ShellState();

        assertEquals(new ShellStateSnapshot("", ""), state.snapshot());
    }

    @Test
    void replacesTheWholeSnapshotOnEachUpdateSoReadersNeverSeeMixedPairs() {
        ShellState state = new ShellState();

        state.selectToolWindow("acp.agents");
        assertEquals(new ShellStateSnapshot("acp.agents", ""), state.snapshot());

        state.selectFile("vintelli/pom.xml");
        assertEquals(new ShellStateSnapshot("acp.agents", "vintelli/pom.xml"), state.snapshot());
    }

    @Test
    void anotherThreadReadsOnlyWholeSnapshotsWhileTheShellUpdates() throws Exception {
        ShellState state = new ShellState();
        state.selectToolWindow("run");
        state.selectFile("vintelli/pom.xml");
        List<ShellStateSnapshot> expected = List.of(
                new ShellStateSnapshot("run", "vintelli/pom.xml"),
                new ShellStateSnapshot("acp.agents", "vintelli/pom.xml"));

        Thread updater = new Thread(() -> {
            for (int round = 0; round < 500; round++) {
                state.selectToolWindow("acp.agents");
                state.selectToolWindow("run");
            }
        });
        updater.start();
        try {
            for (int round = 0; round < 500; round++) {
                ShellStateSnapshot read = state.snapshot();
                assertTrue(expected.contains(read), "torn or unknown snapshot: " + read);
            }
        } finally {
            updater.join(TimeUnit.SECONDS.toMillis(5));
        }
        CompletableFuture<ShellStateSnapshot> settle = CompletableFuture.supplyAsync(state::snapshot);
        assertTrue(expected.contains(settle.get(5, TimeUnit.SECONDS)),
                "unexpected snapshot after updates: " + settle.get(5, TimeUnit.SECONDS));
    }
}
