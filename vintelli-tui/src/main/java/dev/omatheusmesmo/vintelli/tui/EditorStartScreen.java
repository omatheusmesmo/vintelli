package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.RichTextElement;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The empty state of the editor pane, shown while no document is open: the
 * project name and the main shortcuts. Each key label is read from the active
 * keymap when the pane renders, so a custom keymap shows its own chords, and an
 * action without a chord is left out. The block is centred in both directions.
 */
final class EditorStartScreen {

    private static final String COLUMN_GAP = "    ";
    private static final String TEXT_ROLE = "editor-start-text";
    private static final String HINT_ROLE = "editor-start-hint";
    private static final List<ShortcutEntry> SHORTCUTS = List.of(
            new ShortcutEntry(KeyActions.GO_TO_FILE, "Go to File"),
            new ShortcutEntry(KeyActions.FIND_IN_FILES, "Find in Files"),
            new ShortcutEntry(KeyActions.SEARCH_EVERYWHERE, "Search Everywhere"),
            new ShortcutEntry(KeyActions.MENU, "Open Menu"),
            new ShortcutEntry(KeyActions.SHELL_SETTINGS, "Open Settings"),
            new ShortcutEntry(KeyActions.KEYMAP_HELP, "Keymap Help"));

    private final Path projectRoot;
    private final Supplier<Keymap> keymap;
    private final ShellThemes themes;

    EditorStartScreen(Path projectRoot, Supplier<Keymap> keymap, ShellThemes themes) {
        this.projectRoot = projectRoot;
        this.keymap = keymap;
        this.themes = themes;
    }

    /**
     * The start screen element, registered as {@code editor-content} like the
     * other editor bodies so the focus and the routing keep working.
     */
    Element element() {
        List<Line> lines = lines(projectName(), shortcuts(keymap.get()));
        RichTextElement content = Toolkit.richText(Text.from(lines)).centered();
        content.id("editor-content");
        content.addClass("editor-content");
        return Toolkit.column(Toolkit.spacer(), content, Toolkit.spacer());
    }

    /**
     * The shortcut rows of a keymap, in the order of the screen. An action the
     * keymap leaves without a chord has no row.
     */
    static List<Shortcut> shortcuts(Keymap keymap) {
        List<Shortcut> shortcuts = new ArrayList<>();
        for (ShortcutEntry entry : SHORTCUTS) {
            if (keymap.isBound(entry.actionId())) {
                shortcuts.add(new Shortcut(entry.label(), keymap.describeBindings(entry.actionId())));
            }
        }
        return shortcuts;
    }

    /**
     * The screen lines: the project name, a blank line, then one row per
     * shortcut with its label and its keys in two aligned columns. Every line
     * is padded to the width of the block, so centring the lines centres the
     * block as a whole.
     */
    List<Line> lines(String projectName, List<Shortcut> shortcuts) {
        int labelWidth = shortcuts.stream().mapToInt(shortcut -> shortcut.label().length()).max().orElse(0);
        int keysWidth = shortcuts.stream().mapToInt(shortcut -> shortcut.keys().length()).max().orElse(0);
        int blockWidth = Math.max(projectName.length(), labelWidth + COLUMN_GAP.length() + keysWidth);
        Style text = styledRole(TEXT_ROLE);
        Style hint = styledRole(HINT_ROLE);
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from(Span.styled(padRight(projectName, blockWidth), text)));
        lines.add(Line.from(Span.styled(padRight("", blockWidth), text)));
        for (Shortcut shortcut : shortcuts) {
            lines.add(Line.from(Span.styled(padRight(shortcut.label(), labelWidth) + COLUMN_GAP, text),
                    Span.styled(padRight(shortcut.keys(), keysWidth), hint)));
        }
        return lines;
    }

    private String projectName() {
        Path name = projectRoot.getFileName();
        return name == null ? projectRoot.toString() : name.toString();
    }

    private static String padRight(String text, int width) {
        return text + " ".repeat(Math.max(0, width - text.length()));
    }

    private Style styledRole(String role) {
        return themes.resolver(role).toStyle();
    }

    /**
     * One shortcut row: the label of the action and the chord text the keymap
     * shows for it, for example {@code Ctrl+o}.
     */
    record Shortcut(String label, String keys) {
    }

    private record ShortcutEntry(String actionId, String label) {
    }
}
