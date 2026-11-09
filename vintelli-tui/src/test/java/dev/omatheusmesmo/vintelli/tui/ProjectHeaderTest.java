package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.ProjectHeader.Button;
import dev.omatheusmesmo.vintelli.tui.ProjectHeader.Target;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.tamboui.layout.Rect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectHeaderTest {

    private static final String TITLE = "Project v";

    @Test
    void buttonsSitAtTheRightEdgeWithOneBlankColumnBetweenThem() {
        List<Target> targets = ProjectHeader.targets(new Rect(10, 1, 30, 1), TITLE);

        assertEquals(List.of(
                new Target(KeyActions.PROJECT_VIEW_MENU, new Rect(10, 1, 9 + 2, 1)),
                new Target(KeyActions.PROJECT_NEW_MENU, new Rect(28, 1, 1, 1)),
                new Target(KeyActions.PROJECT_SELECT_OPENED_FILE, new Rect(30, 1, 1, 1)),
                new Target(KeyActions.PROJECT_EXPAND_ALL, new Rect(32, 1, 1, 1)),
                new Target(KeyActions.PROJECT_COLLAPSE_ALL, new Rect(34, 1, 1, 1)),
                new Target(KeyActions.PROJECT_OPTIONS_MENU, new Rect(36, 1, 1, 1)),
                new Target(KeyActions.PROJECT_HIDE, new Rect(38, 1, 1, 1))), targets);
    }

    @Test
    void narrowRowsDropTheButtonsFromTheLeftAndKeepHideLast() {
        List<Target> targets = ProjectHeader.targets(new Rect(0, 0, 20, 1), TITLE);

        assertEquals(List.of(
                KeyActions.PROJECT_VIEW_MENU,
                KeyActions.PROJECT_EXPAND_ALL,
                KeyActions.PROJECT_COLLAPSE_ALL,
                KeyActions.PROJECT_OPTIONS_MENU,
                KeyActions.PROJECT_HIDE), targets.stream().map(Target::actionId).toList());
    }

    @Test
    void aRowWithoutRoomForAButtonKeepsOnlyTheTitle() {
        List<Target> targets = ProjectHeader.targets(new Rect(0, 0, 12, 1), TITLE);

        assertEquals(List.of(KeyActions.PROJECT_VIEW_MENU), targets.stream().map(Target::actionId).toList());
    }

    @Test
    void theTitleClipsToTheRowWhenTheRowIsNarrowerThanIt() {
        List<Target> targets = ProjectHeader.targets(new Rect(0, 0, 5, 1), TITLE);

        assertEquals(new Rect(0, 0, 5, 1), targets.getFirst().cell());
        assertEquals(1, targets.size());
    }

    @Test
    void anEmptyRowHasNoTargets() {
        assertEquals(List.of(), ProjectHeader.targets(Rect.ZERO, TITLE));
    }

    @Test
    void buttonGlyphsFollowTheIconFaceAndTheUnicodeTreeSwitch() {
        assertEquals("", Button.HIDE.glyph(IconMode.NERD, false));
        assertEquals("", Button.HIDE.glyph(IconMode.NERD, true));
        assertEquals("─", Button.HIDE.glyph(IconMode.ASCII, true));
        assertEquals("_", Button.HIDE.glyph(IconMode.ASCII, false));
    }

    @Test
    void theTitleArrowFollowsTheIconFace() {
        assertEquals("Project ", ProjectHeader.title(IconMode.NERD, false));
        assertEquals("Project ▾", ProjectHeader.title(IconMode.ASCII, true));
        assertEquals("Project v", ProjectHeader.title(IconMode.ASCII, false));
    }
}
