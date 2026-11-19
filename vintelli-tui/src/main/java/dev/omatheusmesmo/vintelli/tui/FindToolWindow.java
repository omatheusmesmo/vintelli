package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.navigation.NavigationTarget;
import dev.omatheusmesmo.vintelli.tui.navigation.UsageSearch;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.paragraph.Paragraph;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The Find tool window of the bottom area: the usages of the last Find Usages search, grouped by file. The header
 * reads {@code Usages of helper in 4 files (12)}, a file row shows its usage count, and a usage row shows the line
 * and the source line with the symbol marked. Up and Down move the selection, Enter opens the usage and keeps the
 * focus in the list, F4 opens it and moves the focus to the editor. The window keeps the last search until the next
 * one replaces it.
 *
 * <p>It has no {@code Alt+digit} chord: IntelliJ's {@code Alt+3} is Debug here, and no other digit is free, so
 * {@link #shortcut()} is {@link ToolWindow#NO_SHORTCUT}. Find Usages opens it, and so do its tab, its strip icon and
 * the command palette. It starts hidden, because it only holds the answer of a search.
 */
@Singleton
public final class FindToolWindow implements ToolWindow {

    /** Opens the usage the window selected; {@code focusEditor} moves the focus from the list to the editor. */
    @FunctionalInterface
    public interface Opener {
        void open(NavigationTarget usage, boolean focusEditor);
    }

    private sealed interface Row permits FileRow, UsageRow {
        NavigationTarget target();
    }

    private record FileRow(UsageSearch.FileUsages file) implements Row {
        @Override
        public NavigationTarget target() {
            return file.usages().getFirst();
        }
    }

    private record UsageRow(NavigationTarget target) implements Row {
    }

    private static final String ID = "find";
    private static final String SEARCH_GLYPH = "";
    private static final String EMPTY = "No usages searched yet: press Alt+F7 on a symbol";
    private static final int POSITION_WIDTH = 8;
    private static final int PAGE_ROWS = 8;
    private static final int WHEEL_ROWS = 3;

    private final Provider<ShellThemes> themes;
    private final DoubleClickTracker clicks = new DoubleClickTracker();
    private final LongSupplier nanos;
    private UsageSearch search;
    private List<Row> rows = List.of();
    private int selected;
    private int scroll;
    private Rect lastArea = Rect.ZERO;
    private Opener opener;

    @Inject
    public FindToolWindow(Provider<ShellThemes> themes) {
        this(themes, System::nanoTime);
    }

    FindToolWindow(Provider<ShellThemes> themes, LongSupplier nanos) {
        this.themes = themes;
        this.nanos = nanos;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Find";
    }

    @Override
    public char shortcut() {
        return NO_SHORTCUT;
    }

    @Override
    public int ordinal() {
        return 6;
    }

    @Override
    public String icon() {
        return SEARCH_GLYPH;
    }

    @Override
    public boolean visibleByDefault() {
        return false;
    }

    public void setOpener(Opener opener) {
        this.opener = opener;
    }

    /** Replaces the last search with a new one and selects its first usage. */
    public void show(UsageSearch next) {
        search = next;
        List<Row> built = new ArrayList<>();
        for (UsageSearch.FileUsages file : next.files()) {
            built.add(new FileRow(file));
            file.usages().forEach(usage -> built.add(new UsageRow(usage)));
        }
        rows = List.copyOf(built);
        selected = rows.size() > 1 ? 1 : 0;
        scroll = 0;
        clicks.reset();
    }

    /** The header of the last search, or the hint while none ran. */
    public String header() {
        return search == null ? EMPTY : search.header();
    }

    /** The usage the selection is on, which is the first usage of the file when a file row is selected. */
    public NavigationTarget selectedTarget() {
        return rows.isEmpty() ? null : rows.get(selected).target();
    }

    @Override
    public boolean shouldHandleKeyBeforeShell(KeyEvent event) {
        return Keys.is(event, KeyCode.UP) || Keys.is(event, KeyCode.DOWN) || Keys.is(event, KeyCode.PAGE_UP)
                || Keys.is(event, KeyCode.PAGE_DOWN) || Keys.is(event, KeyCode.ENTER) || Keys.is(event, KeyCode.F4);
    }

    @Override
    public boolean handleKey(KeyEvent event) {
        if (rows.isEmpty()) {
            return false;
        }
        if (Keys.is(event, KeyCode.UP)) {
            move(-1);
        } else if (Keys.is(event, KeyCode.DOWN)) {
            move(1);
        } else if (Keys.is(event, KeyCode.PAGE_UP)) {
            move(-PAGE_ROWS);
        } else if (Keys.is(event, KeyCode.PAGE_DOWN)) {
            move(PAGE_ROWS);
        } else if (Keys.is(event, KeyCode.ENTER)) {
            open(false);
        } else if (Keys.is(event, KeyCode.F4)) {
            open(true);
        } else {
            return false;
        }
        return true;
    }

    @Override
    public boolean handleMouse(MouseEvent event) {
        if (!inside(event.x(), event.y()) || rows.isEmpty()) {
            return false;
        }
        if (event.kind() == MouseEventKind.SCROLL_UP || event.kind() == MouseEventKind.SCROLL_DOWN) {
            int direction = event.kind() == MouseEventKind.SCROLL_UP ? -1 : 1;
            scroll = Math.clamp(scroll + direction * WHEEL_ROWS, 0, maxScroll());
            return true;
        }
        if (!event.isClick() || !event.isLeftButton()) {
            return false;
        }
        int index = scroll + event.y() - lastArea.y() - 1;
        if (event.y() <= lastArea.y() || index >= rows.size()) {
            return false;
        }
        selected = index;
        if (clicks.register(index, nanos.getAsLong())) {
            open(false);
        }
        return true;
    }

    @Override
    public void render(Frame frame, Rect area) {
        lastArea = area;
        Function<String, Style> roles = roles();
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from(Span.styled(fit(header(), area.width()), roles.apply("tool-window-title"))));
        int viewport = Math.max(0, area.height() - 1);
        keepSelectionVisible(viewport);
        for (int index = scroll; index < Math.min(rows.size(), scroll + viewport); index++) {
            lines.add(line(rows.get(index), index == selected, area.width(), roles));
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(lines)).style(roles.apply("tool-window")).build(), area);
    }

    private Line line(Row row, boolean chosen, int width, Function<String, Style> roles) {
        String marker = chosen ? "> " : "  ";
        if (row instanceof FileRow fileRow) {
            UsageSearch.FileUsages file = fileRow.file();
            String directory = file.directory().isEmpty() ? "" : "  " + file.directory();
            String text = marker + file.name() + directory + " (" + file.usages().size() + ")";
            return Line.from(Span.styled(fit(text, width), roles.apply("tool-window-title")));
        }
        NavigationTarget usage = row.target();
        String position = usage.position() + " ".repeat(Math.max(0, POSITION_WIDTH - usage.position().length()));
        String lead = marker + "  " + position + "  ";
        String preview = fit(usage.preview(), Math.max(0, width - CharWidth.of(lead)));
        List<Span> spans = new ArrayList<>();
        spans.add(Span.styled(lead, roles.apply("problem-location")));
        Style text = roles.apply(chosen ? "tool-window-title" : "tool-window");
        int start = usage.matchStart();
        int end = Math.min(preview.length(), start + usage.matchLength());
        if (start < 0 || start >= end) {
            spans.add(Span.styled(preview, text));
        } else {
            spans.add(Span.styled(preview.substring(0, start), text));
            spans.add(Span.styled(preview.substring(start, end), roles.apply("editor-find-match")));
            spans.add(Span.styled(preview.substring(end), text));
        }
        return Line.from(spans);
    }

    private void move(int step) {
        selected = Math.clamp(selected + step, 0, rows.size() - 1);
    }

    private void open(boolean focusEditor) {
        Opener target = opener;
        if (target != null) {
            target.open(selectedTarget(), focusEditor);
        }
    }

    private void keepSelectionVisible(int viewport) {
        if (viewport <= 0) {
            return;
        }
        if (selected < scroll) {
            scroll = selected;
        } else if (selected >= scroll + viewport) {
            scroll = selected - viewport + 1;
        }
        scroll = Math.clamp(scroll, 0, Math.max(0, rows.size() - viewport));
    }

    private int maxScroll() {
        return Math.max(0, rows.size() - Math.max(0, lastArea.height() - 1));
    }

    private boolean inside(int x, int y) {
        return x >= lastArea.x() && x < lastArea.x() + lastArea.width()
                && y >= lastArea.y() && y < lastArea.y() + lastArea.height();
    }

    private Function<String, Style> roles() {
        ShellThemes active = themes == null ? null : themes.get();
        return role -> active == null ? Style.EMPTY : active.resolver(role).toStyle();
    }

    private static String fit(String value, int width) {
        if (width <= 0) {
            return "";
        }
        return CharWidth.of(value) <= width ? value : CharWidth.substringByWidth(value, width);
    }
}
