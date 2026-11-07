package dev.omatheusmesmo.vintelli.tui.layout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void visibilityOnlyLayoutStaysUncustomizedAndAResizeIsPersisted() throws IOException {
        ShellLayoutStore store = new ShellLayoutStore(temporary, temporary.resolve("project"));
        ShellLayout visibilityOnly = new ShellLayout();
        visibilityOnly.toggle("run");
        store.save(visibilityOnly);
        assertFalse(store.load().isSizeCustomized(), "a toggle alone must not switch the sizes to ratios");

        ShellLayout resized = new ShellLayout();
        resized.projectWidth(40, 120);
        store.save(resized);
        assertTrue(store.load().isSizeCustomized(), "a resize must be saved as customized");
    }

    @Test
    void aLayoutFileWrittenBeforeTheSizeFlagKeepsItsRatios() throws IOException {
        ShellLayoutStore store = new ShellLayoutStore(temporary, temporary.resolve("project"));
        store.save(new ShellLayout(0.4, 0.3));
        Files.writeString(store.file(),
                "project.ratio=0.4\nbottom.ratio=0.3\nvisible.windows=run\nproject.visible=true\n");
        ShellLayout loaded = store.load();
        assertEquals(0.4, loaded.projectRatio());
        assertEquals(0.3, loaded.bottomRatio());
        assertTrue(loaded.isSizeCustomized(),
                "a file without the flag was written by the ratio based shell, so its ratios stay in use");
    }

    @Test
    void missingConfigRootDisablesPersistence() throws IOException {
        ShellLayoutStore store = new ShellLayoutStore(null, temporary);
        store.save(new ShellLayout());
        assertEquals(ShellLayout.DEFAULT_PROJECT_RATIO, store.load().projectRatio());
    }
}
