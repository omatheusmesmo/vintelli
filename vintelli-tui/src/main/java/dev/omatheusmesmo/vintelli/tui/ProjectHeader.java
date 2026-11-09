package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Header row of the project pane, laid out like the IntelliJ New UI: the
 * {@code Project} title on the left, whose arrow opens the view menu, and the
 * tree actions on the right with one blank column between them. The row is
 * painted by {@link PaneHeader#paint}, so the title and the rule match the other
 * panes. Each clickable cell is handed to the panel while the row renders, so a
 * pointer press is tested against the cells of the last frame. When the row is
 * narrow, the buttons drop from the left and Hide stays last.
 */
final class ProjectHeader implements Element {

    static final String ID = ProjectPanel.ID + "-header";

    private static final String TITLE = "Project";
    private static final int TITLE_OFFSET = 2;
    private static final int GAP = 1;
    private static final List<Button> GROUP = List.of(Button.NEW, Button.LOCATE, Button.EXPAND_ALL,
            Button.COLLAPSE_ALL, Button.OPTIONS, Button.HIDE);

    /**
     * A header control with its glyph per icon face: Nerd Font private-use glyphs
     * for {@link IconMode#NERD}, Unicode when the tree may use it, and ASCII
     * otherwise.
     */
    enum Button {
        VIEW(KeyActions.PROJECT_VIEW_MENU, "", "▾", "v"),
        NEW(KeyActions.PROJECT_NEW_MENU, "", "+", "+"),
        LOCATE(KeyActions.PROJECT_SELECT_OPENED_FILE, "", "◎", "o"),
        EXPAND_ALL(KeyActions.PROJECT_EXPAND_ALL, "", "⊞", "="),
        COLLAPSE_ALL(KeyActions.PROJECT_COLLAPSE_ALL, "", "⊟", "-"),
        OPTIONS(KeyActions.PROJECT_OPTIONS_MENU, "", "⋮", ":"),
        HIDE(KeyActions.PROJECT_HIDE, "", "─", "_");

        private final String actionId;
        private final String nerd;
        private final String unicode;
        private final String ascii;

        Button(String actionId, String nerd, String unicode, String ascii) {
            this.actionId = actionId;
            this.nerd = nerd;
            this.unicode = unicode;
            this.ascii = ascii;
        }

        String actionId() {
            return actionId;
        }

        String glyph(IconMode mode, boolean unicodeTree) {
            if (mode == IconMode.NERD) {
                return nerd;
            }
            return unicodeTree ? unicode : ascii;
        }

        static Button of(String actionId) {
            for (Button button : values()) {
                if (button.actionId.equals(actionId)) {
                    return button;
                }
            }
            throw new IllegalArgumentException("no project header button for " + actionId);
        }
    }

    /** A clickable cell of the header row and the action it dispatches. */
    record Target(String actionId, Rect cell) {
    }

    private final ProjectPanel panel;
    private final Supplier<ShellThemes> themes;
    private final Supplier<String> focusMark;

    ProjectHeader(ProjectPanel panel, Supplier<ShellThemes> themes, Supplier<String> focusMark) {
        this.panel = panel;
        this.themes = themes;
        this.focusMark = focusMark;
    }

    /**
     * The clickable cells of a header row: the title, which opens the view menu,
     * and the buttons that fit after it. The buttons sit at the right edge with
     * one blank column after the last one, and each one is one blank column
     * apart from the next.
     */
    static List<Target> targets(Rect row, String title) {
        List<Target> targets = new ArrayList<>();
        if (row.isEmpty()) {
            return targets;
        }
        int titleWidth = Math.min(TITLE_OFFSET + title.length(), row.width());
        targets.add(new Target(Button.VIEW.actionId(), new Rect(row.x(), row.y(), titleWidth, 1)));
        int fit = Math.max(0, (row.width() - titleWidth - GAP) / 2);
        int count = Math.min(GROUP.size(), fit);
        int first = row.right() - 2 * count;
        for (int index = 0; index < count; index++) {
            Button button = GROUP.get(GROUP.size() - count + index);
            targets.add(new Target(button.actionId(), new Rect(first + 2 * index, row.y(), 1, 1)));
        }
        return targets;
    }

    static String title(IconMode mode, boolean unicodeTree) {
        return TITLE + " " + Button.VIEW.glyph(mode, unicodeTree);
    }

    @Override
    public void render(Frame frame, Rect area, RenderContext context) {
        if (context instanceof DefaultRenderContext drc) {
            drc.registerElement(this, area);
        }
        IconMode mode = panel.iconMode();
        boolean unicodeTree = panel.unicodeTree();
        String title = title(mode, unicodeTree);
        boolean focused = context.isFocused(ProjectPanel.ID);
        ShellThemes shellThemes = themes.get();
        PaneHeader.paint(frame, area, title, focused, shellThemes, focusMark.get(), true);
        Rect row = new Rect(area.x(), area.y(), area.width(), Math.min(1, area.height()));
        List<Target> targets = targets(row, title);
        panel.headerRendered(row, targets);
        Style style = shellThemes.resolver(focused ? "pane-header-focused" : "pane-header").toStyle();
        Color rule = shellThemes.resolver("pane-header-rule").toStyle().fg().orElse(null);
        for (Target target : targets.subList(Math.min(1, targets.size()), targets.size())) {
            String glyph = Button.of(target.actionId()).glyph(mode, unicodeTree);
            frame.renderWidget(Paragraph.builder().text(Text.from(Line.from(Span.styled(glyph, style)))).build(),
                    target.cell());
            frame.buffer().setStyle(target.cell(), PaneGapElement.headerUnderline(rule));
        }
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        return Size.of(availableWidth >= 0 ? availableWidth : 1, 1);
    }

    @Override
    public Constraint constraint() {
        return Constraint.length(1);
    }

    @Override
    public String id() {
        return ID;
    }
}
