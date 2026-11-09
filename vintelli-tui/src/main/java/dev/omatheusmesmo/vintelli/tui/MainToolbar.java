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
 * The one-row main toolbar of the shell, laid out like the IntelliJ New UI
 * toolbar. The left group holds the menu button, the project and the branch
 * widgets. The right group, right-aligned, holds the run configuration, the
 * Run slot (Run while idle, Stop while a configuration runs), the disabled
 * Debug button, the more button, Search and Settings. Buttons are separated by
 * one blank column and the right group keeps two blank columns from the left
 * group. The widgets are laid out by {@link #layout} from a {@link Model}
 * snapshot, so the cells this element paints and the hit areas
 * {@link #segmentAt} answers for come from the same segments. The model is read
 * from volatile snapshots only: the branch and the run state are refreshed off
 * the render thread, so painting never calls a capability.
 *
 * <p>When the row is too narrow the right group drops its leftmost buttons
 * first, so Settings is the last button to go. The branch widget is dropped
 * only when the left group alone does not fit.
 *
 * <p>While the menu popup is open the left group shows the menu labels in
 * place of the project and branch widgets, and each label sits at the column
 * {@link MenuPopup#labelOffset} gives, which is where its popup panel opens.
 * The labels keep the {@code menu-entry} and {@code menu-entry-active} roles.
 */
final class MainToolbar extends StyledElement<MainToolbar> {

    static final int MENU_ORIGIN = 3;
    static final String ID = "main-toolbar";

    private static final int GROUP_GAP = 2;
    private static final int BUTTON_GAP = 1;
    private static final int MAX_LABEL_WIDTH = 24;

    enum Kind { MENU_BUTTON, PROJECT, BRANCH, MENU, CONFIGURATION, RUN, RUN_STOP, DEBUG, MORE, SEARCH, SETTINGS }

    /**
     * The glyphs of the toolbar, one set per face. Standard Unicode glyphs
     * follow the Unicode probe, Nerd Font glyphs follow {@link IconMode#NERD}.
     */
    record Glyphs(String menu, String dropdown, String play, String stop, String debug, String more,
            String search, String settings) {

        static final Glyphs UNICODE = new Glyphs("≡", "▾", "▶", "■", "◌", "⋮", "⌕", "⚙");
        static final Glyphs ASCII = new Glyphs("=", "v", ">", "#", "d", ":", "/", "*");
        private static final String NERD_DEBUG = "";
        private static final String NERD_SEARCH = "";
        private static final String NERD_SETTINGS = "";

        static Glyphs of(IconMode mode, boolean unicode) {
            if (mode == IconMode.ASCII) {
                return ASCII;
            }
            Glyphs base = unicode ? UNICODE : ASCII;
            if (mode != IconMode.NERD) {
                return base;
            }
            return new Glyphs(base.menu(), base.dropdown(), base.play(), base.stop(), NERD_DEBUG, base.more(),
                    NERD_SEARCH, NERD_SETTINGS);
        }
    }

    /**
     * What the toolbar shows: a {@code null} run label hides the run
     * configuration and the Run slot, {@code running} switches the Run slot to
     * Stop, and {@code activeMenu} is the highlighted label while the popup is
     * open.
     */
    record Model(String project, String branch, String run, boolean running, boolean menuOpen, int activeMenu) {
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
        this(model, menus, Glyphs.of(mode, ProjectTreeGlyphs.supportsUnicode()), themes);
    }

    MainToolbar(Supplier<Model> model, List<String> menus, Glyphs glyphs, Supplier<ShellThemes> themes) {
        this.model = model;
        this.menus = List.copyOf(menus);
        this.glyphs = glyphs;
        this.themes = themes;
        id(ID);
        length(1);
    }

    static String labelFor(String value) {
        return StatusBarLayout.truncate(value, MAX_LABEL_WIDTH);
    }

    /**
     * The segments the toolbar draws in a row of {@code width} cells. The left
     * group keeps the branch while it fits. The right group takes the longest
     * tail of its buttons that fits two cells after the left group, so the
     * leftmost buttons drop first and Settings stays last.
     */
    static List<Segment> layout(int width, Model model, List<String> menus, Glyphs glyphs) {
        if (width <= 0) {
            return List.of();
        }
        List<Item> left = leftItems(model, menus, glyphs);
        if (itemsWidth(left) > width) {
            left = withoutBranch(left);
        }
        List<Item> right = fittingTail(rightItems(model, glyphs), width - itemsWidth(left) - GROUP_GAP);
        List<Segment> segments = new ArrayList<>();
        place(segments, 0, width, left, 0);
        if (!right.isEmpty()) {
            place(segments, width - groupWidth(right), width, right, BUTTON_GAP);
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

    private static List<Item> rightItems(Model model, Glyphs glyphs) {
        List<Item> items = new ArrayList<>();
        if (model.run() != null) {
            items.add(new Item(Kind.CONFIGURATION, " " + labelFor(model.run()) + " " + glyphs.dropdown(), -1));
            items.add(model.running()
                    ? new Item(Kind.RUN_STOP, " " + glyphs.stop(), -1)
                    : new Item(Kind.RUN, " " + glyphs.play(), -1));
        }
        items.add(new Item(Kind.DEBUG, " " + glyphs.debug(), -1));
        items.add(new Item(Kind.MORE, " " + glyphs.more(), -1));
        items.add(new Item(Kind.SEARCH, " " + glyphs.search(), -1));
        items.add(new Item(Kind.SETTINGS, " " + glyphs.settings(), -1));
        return items;
    }

    private static List<Item> withoutBranch(List<Item> items) {
        return items.stream().filter(item -> item.kind() != Kind.BRANCH).toList();
    }

    private static List<Item> fittingTail(List<Item> items, int budget) {
        for (int start = 0; start < items.size(); start++) {
            List<Item> tail = items.subList(start, items.size());
            if (groupWidth(tail) <= budget) {
                return tail;
            }
        }
        return List.of();
    }

    private static int itemsWidth(List<Item> items) {
        return items.stream().mapToInt(item -> CharWidth.of(item.text())).sum();
    }

    private static int groupWidth(List<Item> items) {
        return itemsWidth(items) + BUTTON_GAP * Math.max(0, items.size() - 1);
    }

    private static void place(List<Segment> segments, int start, int width, List<Item> items, int gap) {
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
            x += CharWidth.of(text) + gap;
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
        return switch (segment.kind()) {
            case MENU -> {
                String role = segment.menuIndex() == current.activeMenu() ? "menu-entry-active" : "menu-entry";
                yield theme.resolver(role).toStyle();
            }
            case DEBUG -> base.dim();
            default -> base;
        };
    }

    @Override
    public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
        return Size.of(availableWidth >= 0 ? availableWidth : 1, 1);
    }
}
