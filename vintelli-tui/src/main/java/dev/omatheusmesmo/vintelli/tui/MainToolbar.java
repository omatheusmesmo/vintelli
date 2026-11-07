package dev.omatheusmesmo.vintelli.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.omatheusmesmo.vintelli.tui.menu.MenuPopup;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.widgets.paragraph.Paragraph;

/**
 * The one-row main toolbar of the shell: the menu button, the project and
 * branch widgets on the left and the run widget on the right. The widgets are
 * laid out by {@link #layout} from a {@link Model} snapshot, so the cells this
 * element paints and the hit areas {@link #segmentAt} answers for come from the
 * same segments. The model is read from volatile snapshots only: the branch
 * and the run state are refreshed off the render thread, so painting never
 * calls a capability.
 *
 * <p>While the menu popup is open the left group shows the menu labels in
 * place of the project and branch widgets, and each label sits at the column
 * {@link MenuPopup#labelOffset} gives, which is where its popup panel opens.
 * The labels keep the {@code menu-entry} and {@code menu-entry-active} roles.
 */
final class MainToolbar extends StyledElement<MainToolbar> {

    static final int MENU_ORIGIN = 3;
    static final String ID = "main-toolbar";

    private static final int GAP = 2;
    private static final int MAX_LABEL_WIDTH = 24;

    enum Kind { MENU_BUTTON, PROJECT, BRANCH, MENU, RUN, RUN_STOP }

    /** The glyphs of the toolbar, one set per {@link IconMode} face. */
    record Glyphs(String menu, String dropdown, String play, String stop) {

        static final Glyphs UNICODE = new Glyphs("≡", "▾", "▶", "■");
        static final Glyphs ASCII = new Glyphs("=", "v", ">", "#");

        static Glyphs of(IconMode mode) {
            return mode == IconMode.ASCII ? ASCII : UNICODE;
        }
    }

    /**
     * What the toolbar shows: a {@code null} branch or run label hides that
     * widget, and {@code activeMenu} is the highlighted label while the popup is
     * open.
     */
    record Model(String project, String branch, String run, boolean menuOpen, int activeMenu) {
    }

    /** One drawn piece of the toolbar at a column of its row. */
    record Segment(Kind kind, int x, String text, int menuIndex) {

        int width() {
            return CharWidth.of(text);
        }
    }

    private record Item(Kind kind, String text, int menuIndex) {
    }

    private final Supplier<Model> model;
    private final List<String> menus;
    private final Glyphs glyphs;
    private final Supplier<ShellThemes> themes;
    private volatile Rect lastArea = Rect.ZERO;
    private volatile List<Segment> lastSegments = List.of();

    MainToolbar(Supplier<Model> model, List<String> menus, IconMode mode, Supplier<ShellThemes> themes) {
        this.model = model;
        this.menus = List.copyOf(menus);
        this.glyphs = Glyphs.of(mode);
        this.themes = themes;
        id(ID);
        length(1);
    }

    static String labelFor(String value) {
        return StatusBarLayout.truncate(value, MAX_LABEL_WIDTH);
    }

    /**
     * The segments the toolbar draws in a row of {@code width} cells. The menu
     * button always stays. The run group goes first when the two groups do not
     * fit side by side, then the branch widget, and the project name is clipped
     * last.
     */
    static List<Segment> layout(int width, Model model, List<String> menus, Glyphs glyphs) {
        if (width <= 0) {
            return List.of();
        }
        List<Item> left = leftItems(model, menus, glyphs);
        List<Item> right = model.run() == null ? List.of() : runItems(model, glyphs);
        if (!right.isEmpty() && itemsWidth(left) + GAP + itemsWidth(right) > width) {
            right = List.of();
        }
        if (itemsWidth(left) > width) {
            left = left.stream().filter(item -> item.kind() != Kind.BRANCH).toList();
        }
        List<Segment> segments = new ArrayList<>();
        place(segments, 0, width, left);
        if (!right.isEmpty()) {
            place(segments, width - itemsWidth(right), width, right);
        }
        return List.copyOf(segments);
    }

    private static List<Item> leftItems(Model model, List<String> menus, Glyphs glyphs) {
        List<Item> items = new ArrayList<>();
        items.add(new Item(Kind.MENU_BUTTON, " " + glyphs.menu() + " ", -1));
        if (model.menuOpen()) {
            for (int index = 0; index < menus.size(); index++) {
                items.add(new Item(Kind.MENU, " " + menus.get(index) + " ", index));
            }
            return items;
        }
        items.add(new Item(Kind.PROJECT, " " + labelFor(model.project()) + " " + glyphs.dropdown() + " ", -1));
        if (model.branch() != null) {
            items.add(new Item(Kind.BRANCH, " " + labelFor(model.branch()) + " " + glyphs.dropdown() + " ", -1));
        }
        return items;
    }

    private static List<Item> runItems(Model model, Glyphs glyphs) {
        return List.of(
                new Item(Kind.RUN, " " + glyphs.play() + " " + labelFor(model.run()) + " ", -1),
                new Item(Kind.RUN_STOP, " " + glyphs.stop() + " ", -1));
    }

    private static int itemsWidth(List<Item> items) {
        return items.stream().mapToInt(item -> CharWidth.of(item.text())).sum();
    }

    private static void place(List<Segment> segments, int start, int width, List<Item> items) {
        int x = start;
        for (Item item : items) {
            if (x >= width) {
                return;
            }
            String text = CharWidth.substringByWidth(item.text(), width - x);
            if (text.isEmpty()) {
                return;
            }
            segments.add(new Segment(item.kind(), x, text, item.menuIndex()));
            x += CharWidth.of(text);
        }
    }

    /** The segment under the terminal cell, using the geometry of the last render. */
    Optional<Segment> segmentAt(int x, int y) {
        Rect area = lastArea;
        if (area.isEmpty() || y != area.y() || x < area.x() || x >= area.x() + area.width()) {
            return Optional.empty();
        }
        int column = x - area.x();
        return lastSegments.stream()
                .filter(segment -> column >= segment.x() && column < segment.x() + segment.width())
                .findFirst();
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
        Model current = model.get();
        List<Segment> segments = layout(area.width(), current, menus, glyphs);
        lastArea = area;
        lastSegments = segments;
        ShellThemes theme = themes.get();
        Style base = theme.resolver("toolbar-widget").toStyle();
        List<Span> spans = new ArrayList<>();
        int cursor = 0;
        for (Segment segment : segments) {
            if (segment.x() > cursor) {
                spans.add(Span.styled(" ".repeat(segment.x() - cursor), base));
            }
            spans.add(Span.styled(segment.text(), styleOf(segment, current, theme, base)));
            cursor = segment.x() + segment.width();
        }
        if (cursor < area.width()) {
            spans.add(Span.styled(" ".repeat(area.width() - cursor), base));
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(Line.from(spans))).build(), area);
    }

    private static Style styleOf(Segment segment, Model current, ShellThemes theme, Style base) {
        if (segment.kind() != Kind.MENU) {
            return base;
        }
        String role = segment.menuIndex() == current.activeMenu() ? "menu-entry-active" : "menu-entry";
        return theme.resolver(role).toStyle();
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        return Size.of(availableWidth >= 0 ? availableWidth : 1, 1);
    }
}
