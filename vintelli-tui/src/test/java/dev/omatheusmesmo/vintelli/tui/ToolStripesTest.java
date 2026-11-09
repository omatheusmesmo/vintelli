package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import org.junit.jupiter.api.Test;

class ToolStripesTest {

    private static final FakeToolWindow EXPLORER = window("explorer", "Explorer", "", ToolWindow.Anchor.LEFT);
    private static final FakeToolWindow RUN = window("run", "Run", "", ToolWindow.Anchor.BOTTOM);
    private static final FakeToolWindow ACP = window("acp.agents", "ACP", "", ToolWindow.Anchor.RIGHT);
    private static final FakeToolWindow STRUCTURE = window("structure", "Structure", "", ToolWindow.Anchor.RIGHT);

    @Test
    void usesTheWindowIconInNerdModeWhenItIsOneCellWide() {
        assertEquals("R", ToolStripes.glyph(window("terminal", "Terminal", "R", ToolWindow.Anchor.BOTTOM), IconMode.NERD));
    }

    @Test
    void fallsBackToTheUpperCaseTitleLetterInAsciiMode() {
        assertEquals("T", ToolStripes.glyph(window("terminal", "terminal", "R", ToolWindow.Anchor.BOTTOM), IconMode.ASCII));
    }

    @Test
    void fallsBackToTheTitleLetterWhenTheIconIsWiderThanOneCell() {
        assertEquals("P", ToolStripes.glyph(window("problems", "Problems", "機", ToolWindow.Anchor.BOTTOM), IconMode.NERD));
    }

    @Test
    void showsAQuestionMarkForAWindowWithoutATitle() {
        assertEquals("?", ToolStripes.glyph(window("blank", "", "", ToolWindow.Anchor.BOTTOM), IconMode.NERD));
    }

    @Test
    void marksOnlyTheActiveWindowOfEachAnchorAndStacksBottomIconsLast() {
        StripHost host = new StripHost();
        host.activate(ToolWindow.Anchor.LEFT, EXPLORER);
        host.activate(ToolWindow.Anchor.BOTTOM, RUN);
        ToolStripes stripes = stripes(host);

        assertEquals(List.of(
                new ToolStripElement.Icon(ToolStripes.PROJECT_STRIP_ID, ToolStripes.PROJECT_GLYPH, true, false),
                new ToolStripElement.Icon("explorer", "E", true, false),
                new ToolStripElement.Icon("run", "R", true, true)), stripes.leftIcons());
    }

    @Test
    void theProjectIconIsItsLetterInTheAsciiFace() {
        Map<ToolWindow.Anchor, List<ToolWindow>> byAnchor = new EnumMap<>(ToolWindow.Anchor.class);
        ToolStripes stripes = new ToolStripes(byAnchor, IconMode.ASCII, new StripHost());

        assertEquals("P", stripes.leftIcons().getFirst().glyph());
    }

    @Test
    void marksTheActiveRightWindowAndLeavesTheOthersInactive() {
        StripHost host = new StripHost();
        host.activate(ToolWindow.Anchor.RIGHT, STRUCTURE);
        ToolStripes stripes = stripes(host);

        assertEquals(List.of(
                new ToolStripElement.Icon("acp.agents", "A", false, false),
                new ToolStripElement.Icon("structure", "S", true, false)), stripes.rightIcons());
    }

    @Test
    void hasNoRightStripWithoutRightWindows() {
        ToolStripes stripes = new ToolStripes(Map.of(ToolWindow.Anchor.LEFT, List.of(EXPLORER)),
                IconMode.NERD, new StripHost());

        assertNull(stripes.rightElement());
        assertNotNull(stripes.leftElement());
    }

    @Test
    void clickingTheActiveVisibleWindowHidesItAndClickingAnotherOneActivatesIt() {
        StripHost host = new StripHost();
        host.activate(ToolWindow.Anchor.RIGHT, STRUCTURE);
        ToolStripes stripes = stripes(host);

        stripes.click("structure");
        stripes.click("acp.agents");
        stripes.click(ToolStripes.PROJECT_STRIP_ID);

        assertEquals(List.of("toggle:structure", "switch:acp.agents", "project"), host.events);
    }

    private static ToolStripes stripes(StripHost host) {
        Map<ToolWindow.Anchor, List<ToolWindow>> byAnchor = new EnumMap<>(ToolWindow.Anchor.class);
        byAnchor.put(ToolWindow.Anchor.LEFT, List.of(EXPLORER));
        byAnchor.put(ToolWindow.Anchor.BOTTOM, List.of(RUN));
        byAnchor.put(ToolWindow.Anchor.RIGHT, List.of(ACP, STRUCTURE));
        return new ToolStripes(byAnchor, IconMode.NERD, host);
    }

    private static FakeToolWindow window(String id, String title, String icon, ToolWindow.Anchor anchor) {
        return new FakeToolWindow(id, title, 'x', 0, "", false, anchor) {
            @Override
            public String icon() {
                return icon;
            }
        };
    }

    private static final class StripHost implements ToolStripes.Host {

        private final Map<ToolWindow.Anchor, ToolWindow> active = new EnumMap<>(ToolWindow.Anchor.class);
        private final List<String> events = new ArrayList<>();

        void activate(ToolWindow.Anchor anchor, ToolWindow window) {
            active.put(anchor, window);
        }

        @Override
        public boolean projectVisible() {
            return true;
        }

        @Override
        public boolean windowVisible(String id) {
            return true;
        }

        @Override
        public ToolWindow activeWindow(ToolWindow.Anchor anchor) {
            return active.get(anchor);
        }

        @Override
        public void toggleProjectPanel() {
            events.add("project");
        }

        @Override
        public void switchTo(ToolWindow window) {
            events.add("switch:" + window.id());
        }

        @Override
        public void toggleWindow(String id) {
            events.add("toggle:" + id);
        }
    }
}
