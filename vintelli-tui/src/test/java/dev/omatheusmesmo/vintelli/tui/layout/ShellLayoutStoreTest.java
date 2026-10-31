package dev.omatheusmesmo.vintelli.tui.layout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ShellLayoutStoreTest {
    @TempDir
    Path temporary;

    @Test
    void roundTripsRatiosSeparatelyPerProjectAndReplacesPreviousState() throws IOException {
        Path config = temporary.resolve("config");
        ShellLayoutStore first = new ShellLayoutStore(config, temporary.resolve("first"));
        ShellLayoutStore second = new ShellLayoutStore(config, temporary.resolve("second"));
        ShellLayout layout = new ShellLayout(0.4, 0.3);
        first.save(layout);
        assertEquals(0.4, first.load().projectRatio());
        assertEquals(0.3, first.load().bottomRatio());
        assertNotEquals(first.load().projectRatio(), second.load().projectRatio());
        first.save(new ShellLayout(0.5, 0.4));
        assertEquals(0.5, first.load().projectRatio());
        try (var files = Files.list(first.file().getParent())) {
            assertEquals(1, files.count(), "atomic replacement must leave no temporary files");
        }
    }

    @Test
    void corruptAndIncompleteStateFallBackToDefaults() throws IOException {
        ShellLayoutStore store = new ShellLayoutStore(temporary, temporary.resolve("project"));
        store.save(new ShellLayout(0.4, 0.3));
        Files.writeString(store.file(), "project.ratio=invalid\nbottom.ratio=0.3\n");
        assertEquals(ShellLayout.DEFAULT_PROJECT_RATIO, store.load().projectRatio());
        Files.writeString(store.file(), "project.ratio=0.4\n");
        assertEquals(ShellLayout.DEFAULT_PROJECT_RATIO, store.load().projectRatio());
        Files.writeString(store.file(), "project.ratio=NaN\nbottom.ratio=Infinity\n");
        assertEquals(ShellLayout.DEFAULT_BOTTOM_RATIO, store.load().bottomRatio());
    }

    @Test
    void missingConfigRootDisablesPersistence() throws IOException {
        ShellLayoutStore store = new ShellLayoutStore(null, temporary);
        store.save(new ShellLayout());
        assertEquals(ShellLayout.DEFAULT_PROJECT_RATIO, store.load().projectRatio());
    }
}
