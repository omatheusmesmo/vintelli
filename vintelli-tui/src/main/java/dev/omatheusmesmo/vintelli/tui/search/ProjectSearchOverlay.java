package dev.omatheusmesmo.vintelli.tui.search;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;

/** Immediate-mode renderer and keyboard handler for project search. */
public final class ProjectSearchOverlay {
    private static final Style SELECTED = Style.create().reversed().bold();
    private final ProjectSearchModel model;

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
            return;
        }
        int width = Math.min(76, area.width() - 2);
        int height = Math.min(area.height() - 2, Math.max(5, Math.min(model.size() + 3, 20)));
        Rect panel = new Rect(area.x() + (area.width() - width) / 2,
                area.y() + (area.height() - height) / 2, width, height);
        frame.renderWidget(Clear.INSTANCE, panel);
        String title = model.mode() == ProjectSearchModel.Mode.FILES ? "Go to File" : "Find in Files";
        Block block = Block.builder().title(title).borders(Borders.ALL).borderType(BorderType.ROUNDED).build();
        frame.renderWidget(block, panel);
        Rect inner = block.inner(panel);
        List<Line> lines = new ArrayList<>();
        String prompt = model.mode() == ProjectSearchModel.Mode.TEXT && model.regex() ? "> /" : "> ";
        lines.add(Line.from(Span.styled(prompt + model.query(), Style.create().bold())));
        int visibleRows = Math.max(0, inner.height() - 1);
        int first = Math.max(0, model.selected() - Math.max(0, visibleRows - 1));
        if (model.size() == 0 && visibleRows > 0) {
            String message = model.error() != null ? model.error()
                    : model.searching() ? "Searching…" : "No results";
            lines.add(Line.from(Span.styled(fit(message, inner.width()), Style.create().dim())));
        } else if (model.mode() == ProjectSearchModel.Mode.FILES) {
            List<FileMatch> results = model.files();
            appendFiles(lines, results, first, visibleRows, model.selected(), inner.width());
        } else {
            List<TextMatch> results = model.text();
            appendText(lines, results, first, visibleRows, model.selected(), inner.width());
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(lines)).build(), inner);
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

    public record Target(Path file, int line) { }
}
