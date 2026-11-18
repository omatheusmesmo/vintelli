package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
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
 * When the pane is too narrow for the labels and their keys side by side, only
 * the labels are drawn, so no chord is cut short.
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
     * other editor bodies so the focus and the routing keep working. The lines
     * are chosen when the element renders, from the width it is given.
     */
    Element element() {
        return new Content(projectName(), shortcuts(keymap.get()));
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
     * The width of the labels and keys side by side, with the gap between them.
     * The keys column is drawn only from this width on.
     */
    static int fullBlockWidth(List<Shortcut> shortcuts) {
        return labelWidth(shortcuts) + COLUMN_GAP.length() + keysWidth(shortcuts);
    }

    /**
     * The screen lines: the project name, a blank line, then one row per
     * shortcut with its label and its keys in two aligned columns. When the
     * width cannot hold both columns, each row is its label alone. Every line
     * is padded to the width of the block, so centring the lines centres the
     * block as a whole.
     */
    List<Line> lines(String projectName, List<Shortcut> shortcuts, int width) {
        boolean withKeys = fullBlockWidth(shortcuts) <= width;
        int labelWidth = labelWidth(shortcuts);
        int keysWidth = keysWidth(shortcuts);
        int blockWidth = withKeys
                ? Math.max(projectName.length(), fullBlockWidth(shortcuts))
                : Math.max(projectName.length(), labelWidth);
        Style text = styledRole(TEXT_ROLE);
        Style hint = styledRole(HINT_ROLE);
        List<Line> lines = new ArrayList<>();
        lines.add(Line.from(Span.styled(padRight(projectName, blockWidth), text)));
        lines.add(Line.from(Span.styled(padRight("", blockWidth), text)));
        for (Shortcut shortcut : shortcuts) {
            if (withKeys) {
                lines.add(Line.from(Span.styled(padRight(shortcut.label(), labelWidth) + COLUMN_GAP, text),
                        Span.styled(padRight(shortcut.keys(), keysWidth), hint)));
            } else {
                lines.add(Line.from(Span.styled(padRight(shortcut.label(), blockWidth), text)));
            }
        }
        return lines;
    }

    private static int labelWidth(List<Shortcut> shortcuts) {
        return shortcuts.stream().mapToInt(shortcut -> shortcut.label().length()).max().orElse(0);
    }

    private static int keysWidth(List<Shortcut> shortcuts) {
        return shortcuts.stream().mapToInt(shortcut -> shortcut.keys().length()).max().orElse(0);
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
     * The start screen as an element of its own, so the lines follow the width
     * of the area the editor pane gives it at render time.
     */
    private final class Content implements Element {

        private final String projectName;
        private final List<Shortcut> shortcuts;

        private Content(String projectName, List<Shortcut> shortcuts) {
            this.projectName = projectName;
            this.shortcuts = shortcuts;
        }

        @Override
        public void render(Frame frame, Rect area, RenderContext context) {
            RichTextElement content = Toolkit.richText(Text.from(lines(projectName, shortcuts, area.width())))
                    .centered();
            Toolkit.column(Toolkit.spacer(), content, Toolkit.spacer()).render(frame, area, context);
        }

        @Override
        public Size preferredSize(int availableWidth, int availableHeight, RenderContext context) {
            return Size.UNKNOWN;
        }

        @Override
        public String id() {
            return "editor-content";
        }
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
