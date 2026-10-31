package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.omatheusmesmo.vintelli.tui.ToolWindow;
import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.bindings.Bindings;
import dev.tamboui.tui.bindings.DefaultBindings;
import dev.tamboui.tui.bindings.InputTrigger;
import dev.tamboui.tui.bindings.KeyTrigger;
import dev.tamboui.tui.error.TuiException;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static dev.omatheusmesmo.vintelli.tui.keymap.KeyActions.KEYMAP_HELP;
import static dev.omatheusmesmo.vintelli.tui.keymap.KeyActions.MOVE_DOWN;
import static dev.omatheusmesmo.vintelli.tui.keymap.KeyActions.MOVE_UP;
import static dev.omatheusmesmo.vintelli.tui.keymap.KeyActions.QUIT;
import static dev.omatheusmesmo.vintelli.tui.keymap.KeyActions.SEARCH_EVERYWHERE;
import static dev.omatheusmesmo.vintelli.tui.keymap.KeyActions.activateToolWindow;
import static dev.omatheusmesmo.vintelli.tui.keymap.KeyActions.toggleToolWindow;
import static dev.omatheusmesmo.vintelli.tui.keymap.KeyActions.reservedActionIds;

/**
 * The active keymap of the TUI shell: named actions, taken from the IntelliJ
 * action ids, mapped to key triggers of the TamboUI {@link Bindings} layer.
 * The shell answers to events it matched by action id only, so swapping the
 * profile or the user overrides changes every binding without code changes.
 *
 * <p>Profiles: {@link KeymapProfile#DEFAULT intellij} by default, {@code vim}
 * as the alternative, selected through the {@code vintelli.keymap} system
 * property. A user keymap file in the config directory
 * ({@code $XDG_CONFIG_HOME/vintelli/keymap.properties} on Linux, {@code
 * APPDATA} on Windows, the shared {@code vintelli.config.dir} override, or the
 * more specific {@code vintelli.keymap.dir} property override)
 * is layered on top in the TamboUI format: an action named in the file loses
 * the profile triggers and keeps exactly the ones the file lists, every other
 * action keeps the profile bindings. The file is validated: unknown action
 * names fail fast naming the file and the line, triggers bound to two
 * different actions fail fast, actions that must survive a capturing window
 * (the palette, the help overlay and the tool window activations) must keep
 * at least one Ctrl/Alt/F-key chord and must not be reached by bare printable
 * characters, which would eat text typed into a capturing window or the
 * palette filter.
 *
 * <p>Built-in TamboUI preset sets keep bare {@code q} and {@code Q} on
 * {@code quit}, which would kill a capturing text input, so every profile
 * rebuilds {@code quit} as {@code Ctrl+C} only, answered while the shell has
 * the focus. Every tool window gets its activation action bound to
 * {@code Alt+<shortcut>} as the keystroke that survives a capturing window,
 * and to the bare shortcut, which switches when no input has focus. The
 * palette chord and the help chord are {@link #reserved} in the sense of the
 * {@link dev.omatheusmesmo.vintelli.tui.ToolWindow} SPI: they keep working
 * while an input has focus.
 */
public final class Keymap {

    static final String PROFILE_PROPERTY = "vintelli.keymap";
    private static final String USER_KEYMAP_FILE = "vintelli/keymap.properties";

    private final KeymapProfile profile;
    private final String source;
    private final Bindings bindings;
    private final Bindings reserved;
    private final List<KeyAction> actions;

    /**
     * Keymap of the running shell: profile from {@code vintelli.keymap} and the
     * user keymap file from the config directory when it is present. Built
     * lazily and only for the TUI session, so a malformed user file never
     * aborts a headless or MCP-only process.
     */
    public static Keymap defaultKeymap(List<ToolWindow> toolWindows) {
        return defaultKeymap(toolWindows,
                System.getProperty(PROFILE_PROPERTY, KeymapProfile.DEFAULT.displayName()),
                ConfigDirectory.userKeymapFile());
    }

    /**
     * Default keymap with the profile name and the config directory passed in;
     * the test seam for the resolution that the running shell does itself.
     */
    static Keymap defaultKeymap(List<ToolWindow> toolWindows, String profileName, Path configRoot) {
        Path userKeymapFile = configRoot == null
                ? null
                : configRoot.resolve("vintelli").resolve("keymap.properties");
        return load(KeymapProfile.fromName(profileName), toolWindows, userKeymapFile);
    }

    /**
     * Keymap of one profile without user overrides; the test and inspection
     * entry point.
     */
    public static Keymap ofProfile(KeymapProfile profile, List<ToolWindow> toolWindows) {
        return load(profile, toolWindows, null);
    }

    /**
     * Keymap of one profile with an optional user keymap file on top.
     */
    public static Keymap load(KeymapProfile profile, List<ToolWindow> toolWindows, Path userKeymapFile) {
        Bindings bindings = buildBindings(profile, toolWindows);
        List<KeyAction> catalog = buildCatalog(toolWindows);
        Set<String> reservedIds = reservedActionIds(toolWindows);
        String source = profile.displayName();
        if (userKeymapFile != null && Files.isRegularFile(userKeymapFile)) {
            bindings = overlayUserKeymapFile(userKeymapFile, bindings, knownActionIds(catalog, bindings),
                    reservedIds);
            source = profile.displayName() + " + user file";
        }
        return new Keymap(profile, source, bindings, buildReservedBindings(bindings, reservedIds), catalog);
    }

    private Keymap(KeymapProfile profile, String source, Bindings bindings, Bindings reserved,
            List<KeyAction> actions) {
        this.profile = profile;
        this.source = source;
        this.bindings = bindings;
        this.reserved = reserved;
        this.actions = List.copyOf(actions);
    }

    private static Bindings buildBindings(KeymapProfile profile, List<ToolWindow> toolWindows) {
        Bindings.Builder builder = profile.navigation().toBuilder();
        builder.unbind(QUIT).bind(KeyTrigger.ctrl('c'), QUIT);
        builder.bind(KeyTrigger.ctrl('p'), SEARCH_EVERYWHERE);
        builder.bind(KeyTrigger.key(KeyCode.F1), KEYMAP_HELP);
        builder.bind(KeyTrigger.key(KeyCode.F10), KeyActions.MENU);
        builder.bind(KeyTrigger.key(KeyCode.ENTER), KeyActions.OPEN_FILE);
        builder.bind(KeyTrigger.ctrl('s'), KeyActions.SAVE);
        builder.bind(KeyTrigger.ctrl('z'), KeyActions.UNDO);
        builder.bind(KeyTrigger.ctrl('y'), KeyActions.REDO);
        builder.bind(KeyTrigger.ctrl('f'), KeyActions.FIND);
        builder.bind(KeyTrigger.ctrl('w'), KeyActions.CLOSE_TAB);
        builder.bind(KeyTrigger.alt(']'), KeyActions.NEXT_TAB);
        builder.bind(KeyTrigger.alt('['), KeyActions.PREVIOUS_TAB);
        builder.bind(KeyTrigger.key(KeyCode.F12), KeyActions.DEFINITION);
        builder.bind(KeyTrigger.alt('u'), KeyActions.USAGES);
        builder.bind(KeyTrigger.key(KeyCode.F8), KeyActions.NEXT_PROBLEM);
        builder.bind(KeyTrigger.ctrl('o'), KeyActions.GO_TO_FILE);
        builder.bind(KeyTrigger.alt('f'), KeyActions.FIND_IN_FILES);
        builder.bind(KeyTrigger.alt('='), KeyActions.PROJECT_WIDER);
        builder.bind(KeyTrigger.alt('-'), KeyActions.PROJECT_NARROWER);
        builder.bind(KeyTrigger.alt('.'), KeyActions.BOTTOM_TALLER);
        builder.bind(KeyTrigger.alt(','), KeyActions.BOTTOM_SHORTER);
        builder.bind(KeyTrigger.ctrl('t'), bindingName(KeyActions.SWITCH_THEME));
        for (ToolWindow toolWindow : toolWindows) {
            builder.bind(KeyTrigger.alt(toolWindow.shortcut()), activateToolWindow(toolWindow.id()));
            builder.bind(KeyTrigger.ch(toolWindow.shortcut()), activateToolWindow(toolWindow.id()));
        }
        return builder.build();
    }

    private static List<KeyAction> buildCatalog(List<ToolWindow> toolWindows) {
        List<KeyAction> catalog = new ArrayList<>();
        catalog.add(new KeyAction(QUIT, "Quit", ""));
        catalog.add(new KeyAction(MOVE_UP, "Move Selection Up", ""));
        catalog.add(new KeyAction(MOVE_DOWN, "Move Selection Down", ""));
        catalog.add(new KeyAction(SEARCH_EVERYWHERE, "Search Everywhere", "Double Shift"));
        catalog.add(new KeyAction(KEYMAP_HELP, "Keymap Help", ""));
        catalog.addAll(KeyActions.shellActions());
        for (ToolWindow toolWindow : toolWindows) {
            catalog.add(new KeyAction(activateToolWindow(toolWindow.id()),
                    "Activate " + toolWindow.title() + " Tool Window",
                    intellijShortcutOf(toolWindow)));
        }
        catalog.sort(Comparator.comparing(KeyAction::label).thenComparing(KeyAction::id));
        return catalog;
    }

    /**
     * The shortcut IntelliJ itself binds for tools this application mirrors;
     * shown by the palette as a reference for users switching from IntelliJ.
     */
    private static String intellijShortcutOf(ToolWindow toolWindow) {
        return switch (toolWindow.id()) {
            case "run" -> "Alt+4";
            case "terminal" -> "Alt+F12";
            default -> "";
        };
    }

    private static Set<String> knownActionIds(List<KeyAction> catalog, Bindings base) {
        Set<String> known = new LinkedHashSet<>();
        for (KeyAction action : catalog) {
            known.add(action.id());
        }
        if (base instanceof DefaultBindings defaultBindings) {
            known.addAll(defaultBindings.actions());
        }
        return known;
    }

    private static Bindings overlayUserKeymapFile(Path userKeymapFile, Bindings base, Set<String> knownIds,
            Set<String> reservedIds) {
        byte[] content;
        try {
            content = Files.readAllBytes(userKeymapFile);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read user keymap file '" + userKeymapFile + "'", e);
        }
        Properties properties = new Properties();
        Map<String, Integer> lineNumbers;
        try {
            properties.load(new ByteArrayInputStream(content));
            lineNumbers = lineNumbersOfKeys(content);
        } catch (IOException e) {
            throw new IllegalStateException("cannot parse user keymap file '" + userKeymapFile + "'", e);
        }
        for (String action : properties.stringPropertyNames()) {
            if (!knownIds.contains(action)) {
                throw new IllegalStateException("unknown action '" + action + "' in user keymap file '"
                        + userKeymapFile + "' (line " + lineNumbers.getOrDefault(action, 0)
                        + "); known actions: " + String.join(", ", new TreeSet<>(knownIds)));
            }
        }
        try {
            Bindings.Builder builder = base.toBuilder();
            for (String action : properties.stringPropertyNames()) {
                builder.unbind(bindingName(action));
            }
            byte[] layeredContent = translateUserKeymapContent(content, properties.stringPropertyNames());
            Bindings layered = BindingSets.load(new ByteArrayInputStream(layeredContent), builder.build());
            validateReservedChords(userKeymapFile, lineNumbers, layered, properties.stringPropertyNames(),
                    reservedIds);
            validateNoTriggerConflicts(userKeymapFile, lineNumbers, layered, knownIds,
                    properties.stringPropertyNames());
            return layered;
        } catch (IOException | IllegalArgumentException | TuiException e) {
            throw new IllegalStateException(
                    "invalid trigger in user keymap file '" + userKeymapFile + "': " + e.getMessage(), e);
        }
    }

    private static Map<String, Integer> lineNumbersOfKeys(byte[] content) {
        Map<String, Integer> lineNumbers = new HashMap<>();
        String[] lines = new String(content, java.nio.charset.StandardCharsets.UTF_8).split("\n");
        for (int number = 0; number < lines.length; number++) {
            String trimmed = lines[number].trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int separator = firstIndexOfSeparator(trimmed);
            if (separator <= 0) {
                continue;
            }
            lineNumbers.putIfAbsent(trimmed.substring(0, separator).trim(), number + 1);
        }
        return lineNumbers;
    }

    private static int firstIndexOfSeparator(String line) {
        int best = -1;
        int index = 0;
        while (index < line.length()) {
            char current = line.charAt(index);
            if (current == '=' || current == ':') {
                return index;
            }
            if (Character.isWhitespace(current)) {
                return index;
            }
            index++;
        }
        return best;
    }

    private static void validateReservedChords(Path userKeymapFile, Map<String, Integer> lineNumbers,
            Bindings layered, Set<String> mentioned, Set<String> reservedIds) {
        for (String action : new TreeSet<>(mentioned)) {
            if (!reservedIds.contains(action)) {
                continue;
            }
            List<InputTrigger> triggers = layered.triggersFor(bindingName(action));
            List<InputTrigger> barePrintable = new ArrayList<>(triggers.stream()
                    .filter(Keymap::isBarePrintable)
                    .toList());
            boolean activationAction = action.startsWith("Activate") && action.endsWith("ToolWindow");
            if (activationAction && triggers.stream().allMatch(Keymap::isBarePrintable)) {
                throw new IllegalStateException("the activation action '" + action + "' in user keymap file '"
                        + userKeymapFile + "' (line " + lineNumbers.getOrDefault(action, 0)
                        + ") keeps no chord that reaches the shell while an input has focus; bare characters belong to "
                        + "the captured input, bind a Ctrl, Alt or F-key chord additionally");
            }
            if (!activationAction && !barePrintable.isEmpty() && !triggers.isEmpty()) {
                throw new IllegalStateException("the reserved action '" + action + "' in user keymap file '"
                        + userKeymapFile + "' (line " + lineNumbers.getOrDefault(action, 0)
                        + ") must not be reached by bare printable characters, they would eat text typed into a "
                        + "capturing window or the palette filter");
            }
        }
    }

    private static void validateNoTriggerConflicts(Path userKeymapFile, Map<String, Integer> lineNumbers,
            Bindings layered, Set<String> knownIds, Set<String> mentioned) {
        for (String action : new TreeSet<>(mentioned)) {
            for (InputTrigger trigger : layered.triggersFor(bindingName(action))) {
                for (String other : new TreeSet<>(knownIds)) {
                    if (other.equals(action)) {
                        continue;
                    }
                    for (InputTrigger otherTrigger : layered.triggersFor(bindingName(other))) {
                        if (otherTrigger.equals(trigger)) {
                            throw new IllegalStateException("trigger '" + trigger.describe()
                                    + "' in user keymap file '" + userKeymapFile
                                    + "' (line " + lineNumbers.getOrDefault(action, 0) + ") is bound to both '"
                                    + action + "' and '" + other + "'");
                        }
                    }
                }
            }
        }
    }

    /**
     * Whether the trigger consumes a plain printable character without Ctrl or
     * Alt: text a capturing window or the palette filter would need.
     */
    private static boolean isBarePrintable(InputTrigger trigger) {
        for (int codePoint = 32; codePoint <= 126; codePoint++) {
            if (trigger.matches(KeyEvent.ofChar(codePoint))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A second binding set with only the reserved actions and their chord
     * triggers: the chords the shell keeps for itself while a capturing window
     * has the keyboard.
     */
    private static Bindings buildReservedBindings(Bindings bindings, Set<String> reservedIds) {
        Bindings.Builder builder = DefaultBindings.builder();
        for (String action : new TreeSet<>(reservedIds)) {
            for (InputTrigger trigger : bindings.triggersFor(bindingName(action))) {
                if (!isBarePrintable(trigger)) {
                    builder.bind(trigger, bindingName(action));
                }
            }
        }
        return builder.build();
    }

    /**
     * Profile the keymap was built from; the user layer is reported by {@link #source()}.
     */
    public KeymapProfile profile() {
        return profile;
    }

    /**
     * Where the bindings come from, for example {@code intellij} or
     * {@code intellij + user file}.
     */
    public String source() {
        return source;
    }

    /**
     * Every action the shell answers to, alphabetically ordered by label; the
     * command palette and the help overlay list exactly this catalog, so no
     * action is unreachable.
     */
    public List<KeyAction> actions() {
        return actions;
    }

    /**
     * Full bindings of the keymap.
     */
    public Bindings bindings() {
        return bindings;
    }

    /**
     * Whether the event triggers the action in this keymap. The shell matches
     * by action id only and never reads concrete keys.
     */
    public boolean matches(KeyEvent event, String actionId) {
        return bindings.matches(event, bindingName(actionId));
    }

    /**
     * Whether the event triggers the action through the reserved chords only,
     * the chords the shell honors while a tool window captures the input:
     * Ctrl/Alt/F-key chords, never bare printable characters.
     */
    public boolean matchesReserved(KeyEvent event, String actionId) {
        return reserved.matches(event, bindingName(actionId));
    }

    /**
     * Human readable description of the shortcuts bound to the action, for
     * example {@code Ctrl+P}; used by the palette and the help overlay. Empty
     * when the user file left the action without triggers.
     */
    public String describeBindings(String actionId) {
        return bindings.describeBindings(bindingName(actionId));
    }

    /**
     * TamboUI binding action names may only contain alphanumerics and
     * underscores, while Vintelli action ids use dotted namespaces such as
     * {@code shell.theme.switch}. Map the latter to a safe binding name so a
     * chord can be stored and matched under the public action id.
     */
    static String bindingName(String actionId) {
        return actionId == null ? null : actionId.replace('.', '_');
    }

    private static byte[] translateUserKeymapContent(byte[] content, java.util.Set<String> actions) {
        String text = new String(content, java.nio.charset.StandardCharsets.UTF_8);
        for (String action : actions) {
            String safe = bindingName(action);
            if (safe.equals(action)) {
                continue;
            }
            text = text.replaceAll("(?m)^(\\s*)" + java.util.regex.Pattern.quote(action)
                    + "(\\s*[=:]\\s*.*)$", "$1" + safe + "$2");
        }
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
