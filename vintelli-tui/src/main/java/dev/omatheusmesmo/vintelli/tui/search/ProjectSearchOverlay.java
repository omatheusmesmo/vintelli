package dev.omatheusmesmo.vintelli.tui.search;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.tui.DoubleClickTracker;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch.FileMatch;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch.TextMatch;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;

/** Immediate-mode renderer and keyboard handler for project search. */
public final class ProjectSearchOverlay {
    private static final Style SELECTED = Style.create().reversed().bold();
    private final ProjectSearchModel model;
    private Rect renderedPanel = Rect.ZERO;
    private Rect renderedInner = Rect.ZERO;
    private ProjectSearchModel.View renderedView;
    private int renderedFirst;
    private int renderedRows;
    private int scrollOffset;
    private final DoubleClickTracker doubleClick = new DoubleClickTracker();

    public ProjectSearchOverlay(ProjectSearchModel model) {
        this.model = model;
    }

    /** Returns the confirmed path and zero-based line, or empty when consumed without confirmation. */
    public Optional<Target> handleKey(KeyEvent key) {
        if (!model.isOpen()) {
            return Optional.empty();
        }
        if (key.isKey(KeyCode.ESCAPE)) {
            model.close();
        } else if (key.isKey(KeyCode.ENTER)) {
            FileMatch file = model.selectedFile();
            if (file != null) {
                model.close();
                return Optional.of(new Target(file.file(), 0));
            }
            TextMatch match = model.selectedText();
            if (match != null) {
                model.close();
                return Optional.of(new Target(match.file(), match.line()));
            }
        } else if (key.isKey(KeyCode.UP)) {
            model.move(-1);
        } else if (key.isKey(KeyCode.DOWN)) {
            model.move(1);
        } else if (key.isKey(KeyCode.BACKSPACE)) {
            model.backspace();
        } else if (key.code() == KeyCode.CHAR && key.codePoint() >= 32 && !key.hasCtrl() && !key.hasAlt()) {
            model.type(key.codePoint());
        }
        return Optional.empty();
    }

    public void render(Frame frame, Rect area) {
        if (!model.isOpen() || area.width() < 8 || area.height() < 4) {
            renderedPanel = Rect.ZERO;
            renderedView = null;
            scrollOffset = 0;
            return;
        }
        ProjectSearchModel.View view = model.view();
        if (renderedView == null || renderedView.mode() != view.mode()
                || !renderedView.query().equals(view.query())) {
            scrollOffset = 0;
        }
        int width = Math.min(76, area.width() - 2);
        int size = view.mode() == ProjectSearchModel.Mode.FILES ? view.files().size() : view.text().size();
        int rows = size + (view.error() != null && size > 0 ? 1 : 0);
        int height = Math.min(area.height() - 2, Math.max(5, Math.min(rows + 3, 20)));
        Rect panel = new Rect(area.x() + (area.width() - width) / 2,
                area.y() + (area.height() - height) / 2, width, height);
        renderedPanel = panel;
        renderedView = view;
        frame.renderWidget(Clear.INSTANCE, panel);
        String title = view.mode() == ProjectSearchModel.Mode.FILES ? "Go to File" : "Find in Files";
        Block block = Block.builder().title(title).borders(Borders.ALL).borderType(BorderType.ROUNDED).build();
        frame.renderWidget(block, panel);
        Rect inner = block.inner(panel);
        renderedInner = inner;
        List<Line> lines = new ArrayList<>();
        String prompt = view.mode() == ProjectSearchModel.Mode.TEXT && view.regex() ? "> /" : "> ";
        lines.add(Line.from(Span.styled(prompt + view.query(), Style.create().bold())));
        int visibleRows = Math.max(0, inner.height() - 1);
        int first = Math.clamp(scrollOffset, 0, Math.max(0, size - visibleRows));
        if (view.selected() < first) {
            first = view.selected();
        } else if (visibleRows > 0 && view.selected() >= first + visibleRows) {
            first = view.selected() - visibleRows + 1;
        }
        scrollOffset = first;
        renderedFirst = first;
        renderedRows = 0;
        if (size == 0 && visibleRows > 0) {
            String message = view.error() != null ? view.error()
                    : view.searching() ? "Searching…" : "No results";
            lines.add(Line.from(Span.styled(fit(message, inner.width()), Style.create().dim())));
        } else if (view.mode() == ProjectSearchModel.Mode.FILES) {
            appendFiles(lines, view.files(), first, visibleRows, view.selected(), inner.width());
            renderedRows = Math.min(visibleRows, Math.max(0, size - first));
            appendIncomplete(lines, inner, view.error());
        } else {
            appendText(lines, view.text(), first, visibleRows, view.selected(), inner.width());
            renderedRows = Math.min(visibleRows, Math.max(0, size - first));
            appendIncomplete(lines, inner, view.error());
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(lines)).build(), inner);
    }

    /** Selects a result on click and opens it on a second click. */
    public Optional<Target> handleMouse(MouseEvent event) {
        if (!model.isOpen()) {
            return Optional.empty();
        }
        if (event.isClick() && !inside(renderedPanel, event.x(), event.y())) {
            model.close();
            renderedView = null;
            return Optional.empty();
        }
        ProjectSearchModel.View current = model.view();
        if (renderedView == null || !sameResults(renderedView, current)
                || !inside(renderedPanel, event.x(), event.y())) {
            return Optional.empty();
        }
        if (event.isScroll() && (event.kind() == MouseEventKind.SCROLL_UP
                || event.kind() == MouseEventKind.SCROLL_DOWN)) {
            doubleClick.reset();
            if (!inside(renderedInner, event.x(), event.y()) || event.y() < renderedInner.y() + 1
                    || renderedRows == 0) {
                return Optional.empty();
            }
            int visibleRows = Math.max(1, renderedInner.height() - 1);
            int size = current.mode() == ProjectSearchModel.Mode.FILES
                    ? current.files().size() : current.text().size();
            int direction = event.kind() == MouseEventKind.SCROLL_UP ? -1 : 1;
            scrollOffset = Math.clamp(scrollOffset + direction * 3, 0, Math.max(0, size - visibleRows));
            model.select(Math.clamp(current.selected(), scrollOffset,
                    Math.min(size - 1, scrollOffset + visibleRows - 1)));
            return Optional.empty();
        }
        if (!event.isClick() || !inside(renderedInner, event.x(), event.y())
                || event.y() < renderedInner.y() + 1) {
            if (event.isClick()) {
                doubleClick.reset();
            }
            return Optional.empty();
        }
        int row = event.y() - renderedInner.y() - 1;
        if (row < 0 || row >= renderedRows) {
            doubleClick.reset();
            return Optional.empty();
        }
        int index = renderedFirst + row;
        model.select(index);
        if (!doubleClick.register(index, System.nanoTime())) {
            return Optional.empty();
        }
        Target target = current.mode() == ProjectSearchModel.Mode.FILES
                ? new Target(current.files().get(index).file(), 0)
                : new Target(current.text().get(index).file(), current.text().get(index).line());
        model.close();
        renderedView = null;
        return Optional.of(target);
    }

    private static boolean inside(Rect rect, int x, int y) {
        return rect.width() > 0 && rect.height() > 0 && x >= rect.x() && x < rect.x() + rect.width()
                && y >= rect.y() && y < rect.y() + rect.height();
    }

    private static boolean sameResults(ProjectSearchModel.View rendered, ProjectSearchModel.View current) {
        return rendered.mode() == current.mode() && rendered.query().equals(current.query())
                && rendered.regex() == current.regex() && rendered.files().equals(current.files())
                && rendered.text().equals(current.text()) && rendered.searching() == current.searching()
                && Objects.equals(rendered.error(), current.error());
    }

    private static void appendFiles(List<Line> lines, List<FileMatch> results, int first, int count,
            int selected, int width) {
        for (int row = 0; row < count && first + row < results.size(); row++) {
            FileMatch match = results.get(first + row);
            String label = fit(match.file().toString().replace('\\', '/'), width);
            lines.add(Line.from(Span.styled((first + row) == selected ? " " + label : " " + label,
                    (first + row) == selected ? SELECTED : Style.create())));
        }
    }

    private static void appendText(List<Line> lines, List<TextMatch> results, int first, int count,
            int selected, int width) {
        for (int row = 0; row < count && first + row < results.size(); row++) {
            TextMatch match = results.get(first + row);
            String path = match.file().toString().replace('\\', '/') + ":" + (match.line() + 1) + ":";
            int excerptWidth = Math.max(0, width - path.length() - 2);
            String label = fit(path + " " + fit(match.text().strip(), excerptWidth), width);
            lines.add(Line.from(Span.styled(" " + label,
                    (first + row) == selected ? SELECTED : Style.create())));
        }
    }

    private static String fit(String value, int width) {
        if (width <= 0) return "";
        return value.length() <= width ? value : value.substring(0, width);
    }

    /** Results already on screen stay visible; the failure surfaces as a trailing line. */
    private void appendIncomplete(List<Line> lines, Rect inner, String error) {
        if (error == null || lines.size() >= inner.height()) {
            return;
        }
        lines.add(Line.from(Span.styled(fit("Results may be incomplete: " + error, inner.width()),
                Style.create().dim())));
    }

    public record Target(Path file, int line) { }
}
