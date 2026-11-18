package dev.omatheusmesmo.vintelli.tui.layout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusEditorLayoutTest {

    private static final Set<String> ALL_WINDOWS = Set.of("run", "terminal", "problems", "acp.agents");

    @TempDir
    Path temporary;

    @Test
    void aLayoutSavedInFocusModeLoadsInFocusModeWithItsStoredWindowsAndSizes() throws IOException {
        ShellLayoutStore store = new ShellLayoutStore(temporary, temporary.resolve("project"));
        ShellLayout focused = new ShellLayout(0.4, 0.3, Set.of("run", "problems"), ALL_WINDOWS);
        focused.setProjectVisible(true);
        focused.setSizeCustomized(true);
        focused.setEditorFocused(true);
        store.save(focused);

        ShellLayout loaded = store.load(ALL_WINDOWS, Set.of("run"));

        assertTrue(loaded.isEditorFocused(), "a layout saved in focus mode starts in focus mode");
        assertEquals(0.4, loaded.projectRatio());
        assertEquals(0.3, loaded.bottomRatio());
        assertTrue(loaded.isProjectVisible(), "the project flag is stored under the focus flag, not replaced by it");
        assertTrue(loaded.isSizeCustomized());
        assertEquals(Set.of("run", "problems"), loaded.visibleWindows(), "the stored windows are kept as saved");
        assertTrue(loaded.isVisible("run"), "the stored visibility is the one that comes back after leaving focus");
        assertFalse(loaded.isShown("run"), "while focused, no tool window is drawn");
    }

    @Test
    void leavingFocusModeIsSavedAsNotFocused() throws IOException {
        ShellLayoutStore store = new ShellLayoutStore(temporary, temporary.resolve("project"));
        ShellLayout layout = new ShellLayout(0.4, 0.3, Set.of("run"), ALL_WINDOWS);
        layout.setEditorFocused(true);
        store.save(layout);
        layout.setEditorFocused(false);
        store.save(layout);

        ShellLayout loaded = store.load(ALL_WINDOWS, Set.of("run"));

        assertFalse(loaded.isEditorFocused());
        assertTrue(loaded.isShown("run"));
    }

    @Test
    void aLayoutFileWithoutTheFocusFlagLoadsAsItDidBefore() throws IOException {
        ShellLayoutStore store = new ShellLayoutStore(temporary, temporary.resolve("project"));
        store.save(new ShellLayout(0.4, 0.3));
        Files.writeString(store.file(),
                "project.ratio=0.4\nbottom.ratio=0.3\nvisible.windows=run,problems\nproject.visible=false\n"
                        + "size.customized=true\n");

        ShellLayout loaded = store.load(ALL_WINDOWS, Set.of("run"));

        assertFalse(loaded.isEditorFocused(), "a file written before Focus Editor starts unfocused");
        assertEquals(Set.of("run", "problems"), loaded.visibleWindows());
        assertTrue(loaded.isShown("problems"));
        assertFalse(loaded.isProjectVisible());
        assertEquals(0.4, loaded.projectRatio());
        assertEquals(0.3, loaded.bottomRatio());
    }

    @Test
    void focusModeHidesWindowsWithoutChangingTheirStoredSet() {
        ShellLayout layout = new ShellLayout(0.4, 0.3, Set.of("run", "acp.agents"), ALL_WINDOWS);

        layout.setEditorFocused(true);

        assertEquals(Set.of("run", "acp.agents"), layout.visibleWindows());
        assertFalse(layout.isShown("run"));
        assertFalse(layout.isShown("acp.agents"));
        layout.setEditorFocused(false);
        assertTrue(layout.isShown("run"));
        assertTrue(layout.isShown("acp.agents"));
        assertFalse(layout.isShown("terminal"));
    }
}
