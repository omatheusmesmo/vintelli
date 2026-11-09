package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.ShellHighlightTarget;
import dev.omatheusmesmo.vintelli.api.ShellUnifiedDiff;
import dev.omatheusmesmo.vintelli.tui.editor.BuiltinEditor;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.RichTextElement;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The editor pane body: the pushed highlight and diff, the open document with
 * its navigation bar, or the start screen while no document is open. It draws the
 * {@link EditorController} state, routes the navigation keys and the editor bar
 * and text pointer events to the controller and the editor. The tab bar and the
 * pane header stay with {@link EditorHeader}.
 */
final class EditorPanel {

    private static final int MAX_EDITOR_DIFF_LINES = 6;
    private static final int MAX_EDITOR_LINE_LENGTH = 40;

    private final EditorController controller;
    private final BuiltinEditor editor;
    private final ShellThemes themes;
    private final EditorStartScreen startScreen;

    EditorPanel(EditorController controller, BuiltinEditor editor, ShellThemes themes, Supplier<Keymap> keymap,
            Path projectRoot) {
        this.controller = controller;
        this.editor = editor;
        this.themes = themes;
        this.startScreen = new EditorStartScreen(projectRoot, keymap, themes);
    }

    /**
     * The body of the editor pane. It runs while the pane renders, which is what
     * refreshes the breadcrumb segments of the open document.
     */
    Element content() {
        ShellHighlightTarget pushed = controller.pushedHighlight();
        ShellUnifiedDiff pushedDiff = controller.pushedDiff();
        if (pushed != null || pushedDiff != null) {
            List<Line> lines = new ArrayList<>();
            if (pushed != null) {
                lines.add(Line.styled("> highlight: " + pushed.file() + ":" + pushed.line(),
                        styledRole("editor-push-highlight")));
            }
            if (pushedDiff != null) {
                renderDiff(lines, pushedDiff);
            }
            RichTextElement content = Toolkit.richText(Text.from(lines));
            content.id("editor-content");
            content.addClass("editor-content");
            return content;
        }
        if (editor != null && editor.activeDocument().isPresent()) {
            controller.refreshNavigation();
            return editor.element();
        }
        return startScreen.element();
    }

    /**
     * Routes a click on the editor bar, a tab or a breadcrumb segment.
     *
     * @return true when the click was taken by the editor bar
     */
    boolean clicked(MouseEvent mouse) {
        return controller.clickEditorBar(mouse.x(), mouse.y());
    }

    /**
     * Routes a press, drag, release or wheel event over the text of the open document.
     *
     * @return true when the editor took the pointer event
     */
    boolean pointer(MouseEvent mouse) {
        return editor != null && editor.handlePointer(mouse);
    }

    /**
     * Routes the keys of the focused navigation bar: arrows move the segment
     * cursor, Enter opens it and Escape returns to the text.
     *
     * @return true when the key was taken by the navigation bar
     */
    boolean handleNavigationKey(KeyEvent key) {
        if (!controller.navigationBarFocused()) {
            return false;
        }
        if (key.isKey(KeyCode.ESCAPE)) {
            controller.returnToEditor();
            return true;
        }
        if (key.isKey(KeyCode.LEFT) || key.isKey(KeyCode.UP)) {
            controller.previousNavigationSegment();
            return true;
        }
        if (key.isKey(KeyCode.RIGHT) || key.isKey(KeyCode.DOWN)) {
            controller.nextNavigationSegment();
            return true;
        }
        return key.isKey(KeyCode.ENTER) && controller.openSelectedSegment();
    }

    private void renderDiff(List<Line> lines, ShellUnifiedDiff pushedDiff) {
        lines.add(Line.styled("diff: " + pushedDiff.title(), styledRole("editor-diff-title")));
        String[] split = pushedDiff.unifiedDiff().split("\n", -1);
        int end = split.length;
        if (end > 1 && split[end - 1].isEmpty()) {
            end--;
        }
        int shown = Math.min(end, MAX_EDITOR_DIFF_LINES);
        for (int index = 0; index < shown; index++) {
            lines.add(Line.styled(clip(split[index]), styledRole(diffRoleOf(split[index]))));
        }
        if (end > shown) {
            lines.add(Line.styled("... (" + (end - shown) + " more lines)", styledRole("editor-diff-marker")));
        }
    }

    private static String diffRoleOf(String diffLine) {
        if (diffLine.startsWith("@@") || diffLine.startsWith("diff ")) {
            return "editor-diff-title";
        }
        if (diffLine.startsWith("+")) {
            return "editor-diff-added";
        }
        if (diffLine.startsWith("-")) {
            return "editor-diff-removed";
        }
        return "editor-diff-context";
    }

    private static String clip(String diffLine) {
        if (diffLine.length() <= MAX_EDITOR_LINE_LENGTH) {
            return diffLine;
        }
        return diffLine.substring(0, MAX_EDITOR_LINE_LENGTH) + " ...";
    }

    private Style styledRole(String role) {
        return themes.resolver(role).toStyle();
    }
}
