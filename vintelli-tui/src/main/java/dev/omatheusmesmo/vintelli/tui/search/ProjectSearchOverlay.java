package dev.omatheusmesmo.vintelli.tui.search;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.omatheusmesmo.vintelli.api.search.ProjectSearch.FileMatch;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch.TextMatch;
import dev.omatheusmesmo.vintelli.tui.OverlayInput;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;

/** Immediate-mode renderer and keyboard handler for project search. */
public final class ProjectSearchOverlay {
    private static final Style SELECTED = Style.create().reversed().bold();

    private final ProjectSearchController controller;

    public ProjectSearchOverlay(ProjectSearchModel model) {
        this.controller = new ProjectSearchController(model);
    }

    /** Returns the confirmed path and zero-based line, or empty when consumed without confirmation. */
    public Optional<Target> handleKey(KeyEvent key) {
        return controller.press(OverlayInput.key(key), key.codePoint()).map(ProjectSearchOverlay::target);
    }

    public void render(Frame frame, Rect area) {
        Optional<ProjectSearchController.Placement> placed = controller.place(OverlayInput.region(area));
        if (placed.isEmpty()) {
            return;
        }
        ProjectSearchController.Placement placement = placed.get();
        ProjectSearchModel.View view = placement.view();
        Rect panel = OverlayInput.rect(placement.panel());
        frame.renderWidget(Clear.INSTANCE, panel);
        String title = view.mode() == ProjectSearchModel.Mode.FILES ? "Go to File" : "Find in Files";
        Block block = Block.builder().title(title).borders(Borders.ALL).borderType(BorderType.ROUNDED).build();
        frame.renderWidget(block, panel);
        Rect inner = OverlayInput.rect(placement.inner());
        List<Line> lines = new ArrayList<>();
        String prompt = view.mode() == ProjectSearchModel.Mode.TEXT && view.regex() ? "> /" : "> ";
        lines.add(Line.from(Span.styled(prompt + view.query(), Style.create().bold())));
        int visibleRows = placement.visibleRows();
        int first = placement.first();
        int size = view.mode() == ProjectSearchModel.Mode.FILES ? view.files().size() : view.text().size();
        if (size == 0 && visibleRows > 0) {
            String message = view.error() != null ? view.error()
                    : view.searching() ? "Searching…" : "No results";
            lines.add(Line.from(Span.styled(fit(message, inner.width()), Style.create().dim())));
        } else if (view.mode() == ProjectSearchModel.Mode.FILES) {
            appendFiles(lines, view.files(), first, visibleRows, view.selected(), inner.width());
            appendIncomplete(lines, inner, view.error());
        } else {
            appendText(lines, view.text(), first, visibleRows, view.selected(), inner.width());
            appendIncomplete(lines, inner, view.error());
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(lines)).build(), inner);
    }

    /** Selects a result on click and opens it on a second click. */
    public Optional<Target> handleMouse(MouseEvent event) {
        return controller.pointer(event.x(), event.y(), event.isClick(), OverlayInput.scrollDirection(event),
                System.nanoTime()).map(ProjectSearchOverlay::target);
    }

    private static Target target(ProjectSearchController.Target target) {
        return new Target(target.file(), target.line());
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
