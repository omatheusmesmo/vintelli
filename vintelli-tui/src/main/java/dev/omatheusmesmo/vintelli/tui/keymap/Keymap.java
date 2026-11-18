package dev.omatheusmesmo.vintelli.tui.keymap;

import dev.omatheusmesmo.vintelli.tui.Keys;
import dev.omatheusmesmo.vintelli.tui.ToolWindow;
import dev.tamboui.terminal.KeyboardProtocol;
import dev.tamboui.tui.bindings.Actions;
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
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

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
 * palette filter; {@code quit} must not be reached by a bare printable
 * character either, which would quit the shell while text is typed into the
 * palette filter or a capturing window.
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
 *
 * <p>The keyboard protocol is a {@link KeyboardMode}: {@code auto} by default,
 * which pushes the Kitty keyboard protocol flags, and {@code legacy} picked with
 * the {@code vintelli.keyboard.protocol} system property. The keymap is built for
 * the mode it reports through {@link #keyboardMode()}, and the shell runner is
 * configured with the same mode, so the two never disagree. Under {@code auto}
 * the profiles keep the TamboUI keypad aliases, bind the word-wise chords and
 * the Ctrl+Shift chords the protocol delivers, and a user file may bind keypad
 * keys and Super, Hyper or Meta chords. Under {@code legacy} the profiles drop
 * the chords only the Kitty protocol could tell apart, see
 * {@link #legacyKeyboardBindings(Bindings)}, and a user file cannot bind a
 * trigger the legacy protocol never sends, see
 * {@link #deliverableInLegacyProtocol(String)}.
 */
public final class Keymap {

    static final String PROFILE_PROPERTY = "vintelli.keymap";
    private static final String USER_KEYMAP_FILE = "vintelli/keymap.properties";
    private static final Set<String> WORD_WISE_ACTIONS = Set.of(Actions.MOVE_WORD_LEFT, Actions.MOVE_WORD_RIGHT,
            Actions.DELETE_WORD_BACKWARD, Actions.DELETE_WORD_FORWARD);
    private static final Set<String> UNSENT_MODIFIERS = Set.of("super", "hyper", "meta");
    private static final Pattern UNSENT_FUNCTION_KEY = Pattern.compile("f(1[3-9]|2[0-9]|3[0-5])");
    private static final String KEYPAD_KEY_PREFIX = "keypad_";
    private static final String KEYPAD_CODE_MARK = "KP_";

    private final KeymapProfile profile;
    private final KeyboardMode keyboardMode;
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
                System.getProperty(KeyboardMode.PROPERTY, KeyboardMode.DEFAULT.displayName()),
                ConfigDirectory.userKeymapFile());
    }

    /**
     * Default keymap with the profile name, the keyboard mode name and the config directory passed in;
     * the test seam for the resolution that the running shell does itself.
     */
    static Keymap defaultKeymap(List<ToolWindow> toolWindows, String profileName, String keyboardName,
            Path configRoot) {
        Path userKeymapFile = configRoot == null
                ? null
                : configRoot.resolve("vintelli").resolve("keymap.properties");
        return load(KeymapProfile.fromName(profileName), KeyboardMode.fromName(keyboardName), toolWindows,
                userKeymapFile);
    }

    /**
     * Keymap of one profile in the default keyboard mode without user overrides; the test and inspection
     * entry point.
     */
    public static Keymap ofProfile(KeymapProfile profile, List<ToolWindow> toolWindows) {
        return ofProfile(profile, KeyboardMode.DEFAULT, toolWindows);
    }

    /**
     * Keymap of one profile in the given keyboard mode without user overrides.
     */
    public static Keymap ofProfile(KeymapProfile profile, KeyboardMode keyboardMode, List<ToolWindow> toolWindows) {
        return load(profile, keyboardMode, toolWindows, null);
    }

    /**
     * Keymap of one profile in the default keyboard mode with an optional user keymap file on top.
     */
    public static Keymap load(KeymapProfile profile, List<ToolWindow> toolWindows, Path userKeymapFile) {
        return load(profile, KeyboardMode.DEFAULT, toolWindows, userKeymapFile);
    }

    /**
     * Keymap of one profile in the given keyboard mode with an optional user keymap file on top.
     */
    public static Keymap load(KeymapProfile profile, KeyboardMode keyboardMode, List<ToolWindow> toolWindows,
            Path userKeymapFile) {
        Bindings bindings = buildBindings(profile, keyboardMode, toolWindows);
        List<KeyAction> catalog = buildCatalog(toolWindows);
        Set<String> reservedIds = reservedActionIds(toolWindows);
        String source = profile.displayName();
        if (userKeymapFile != null && Files.isRegularFile(userKeymapFile)) {
            bindings = overlayUserKeymapFile(userKeymapFile, bindings, knownActionIds(catalog, bindings),
                    reservedIds, keyboardMode);
            source = profile.displayName() + " + user file";
        }
        return new Keymap(profile, keyboardMode, source, bindings, buildReservedBindings(bindings, reservedIds),
                catalog);
    }

    private Keymap(KeymapProfile profile, KeyboardMode keyboardMode, String source, Bindings bindings,
            Bindings reserved, List<KeyAction> actions) {
        this.profile = profile;
        this.keyboardMode = keyboardMode;
        this.source = source;
        this.bindings = bindings;
        this.reserved = reserved;
        this.actions = List.copyOf(actions);
    }

    /**
     * Binding set of a preset in the given keyboard mode: the legacy strip under {@link KeyboardMode#LEGACY},
     * the Kitty set under {@link KeyboardMode#AUTO}.
     */
    static Bindings keyboardBindings(Bindings tamboui, KeyboardMode keyboardMode) {
        return switch (keyboardMode) {
            case LEGACY -> legacyKeyboardBindings(tamboui);
            case AUTO -> kittyKeyboardBindings(tamboui);
        };
    }

    /**
     * Drops the TamboUI 0.7 additions to a preset that the legacy keyboard protocol cannot use. The
     * keypad aliases never fire: in numeric keypad mode a keypad key sends the bytes of its main key,
     * Enter as a carriage return. The word-wise chords would change the text inputs, because a
     * legacy terminal sends Ctrl+w as a plain control byte, so they are removed until the Kitty
     * keyboard protocol decides them. Everything else stays as TamboUI defines it.
     */
    public static Bindings legacyKeyboardBindings(Bindings tamboui) {
        Bindings.Builder builder = tamboui.toBuilder();
        for (String action : WORD_WISE_ACTIONS) {
            builder.unbind(action);
        }
        if (tamboui instanceof DefaultBindings defaultBindings) {
            for (String action : defaultBindings.actions()) {
                List<InputTrigger> triggers = tamboui.triggersFor(action);
                if (triggers.stream().anyMatch(Keymap::isKeypadTwin)) {
                    builder.unbind(action);
                    triggers.stream().filter(trigger -> !isKeypadTwin(trigger))
                            .forEach(trigger -> builder.bind(trigger, action));
                }
            }
        }
        return builder.build();
    }

    /**
     * Word-wise chords of the Kitty keyboard protocol: the preset word actions are replaced, because the
     * presets also bind Ctrl+w to delete a word, and Ctrl+w is Extend Selection in IntelliJ and Close Tab in
     * vim. The chords are the ones a Kitty terminal tells apart from their plain keys, and the editor is the
     * component that consumes them through the TamboUI text area. The keypad aliases stay.
     */
    static Bindings kittyKeyboardBindings(Bindings tamboui) {
        Bindings.Builder builder = tamboui.toBuilder();
        for (String action : WORD_WISE_ACTIONS) {
            builder.unbind(action);
        }
        builder.bind(KeyTrigger.key(KeyCode.LEFT, true, false, false), Actions.MOVE_WORD_LEFT);
        builder.bind(KeyTrigger.key(KeyCode.RIGHT, true, false, false), Actions.MOVE_WORD_RIGHT);
        builder.bind(KeyTrigger.key(KeyCode.BACKSPACE, true, false, false), Actions.DELETE_WORD_BACKWARD);
        builder.bind(KeyTrigger.key(KeyCode.DELETE, true, false, false), Actions.DELETE_WORD_FORWARD);
        return builder.build();
    }

    /**
     * Whether the trigger is a keypad alias of a TamboUI preset: a keypad key without modifiers, such as
     * {@code KP_UP} for {@code Keypad_Up}. A chord on a keypad key, such as {@code Ctrl+Shift+KP_ADD}, is a
     * chord of its own and not an alias.
     */
    static boolean isKeypadTwin(InputTrigger trigger) {
        return trigger.describe().startsWith(KEYPAD_CODE_MARK);
    }

    /**
     * Whether the legacy keyboard protocol can deliver a trigger written in a user keymap file.
     * Super, Hyper and Meta modifiers, keypad keys and the function keys F13 to F35 are never sent
     * by a legacy terminal, so such a trigger would never fire and fails fast instead.
     */
    public static boolean deliverableInLegacyProtocol(String trigger) {
        String[] segments = trigger.split("\\+", -1);
        for (int index = 0; index < segments.length - 1; index++) {
            if (UNSENT_MODIFIERS.contains(segments[index].trim().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        String key = segments[segments.length - 1].trim().toLowerCase(Locale.ROOT);
        return !key.startsWith(KEYPAD_KEY_PREFIX) && !UNSENT_FUNCTION_KEY.matcher(key).matches();
    }

    private static Bindings buildBindings(KeymapProfile profile, KeyboardMode keyboardMode,
            List<ToolWindow> toolWindows) {
        Bindings.Builder builder = profile.navigation(keyboardMode).toBuilder();
        builder.unbind(QUIT).bind(KeyTrigger.ctrl('c'), QUIT);
        builder.bind(KeyTrigger.ctrl('p'), SEARCH_EVERYWHERE);
        builder.bind(KeyTrigger.key(KeyCode.F1), KEYMAP_HELP);
        builder.bind(KeyTrigger.key(KeyCode.F10), KeyActions.MENU);
        builder.bind(KeyTrigger.key(KeyCode.HOME, false, true, false), KeyActions.FOCUS_NAVIGATION_BAR);
        builder.bind(KeyTrigger.key(KeyCode.F7), KeyActions.PROJECT_NEW_FILE);
        builder.bind(KeyTrigger.key(KeyCode.F9), KeyActions.PROJECT_NEW_DIRECTORY);
        builder.bind(KeyTrigger.key(KeyCode.DELETE), KeyActions.PROJECT_DELETE);
        builder.bind(KeyTrigger.key(KeyCode.F5), KeyActions.PROJECT_REFRESH);
        builder.bind(KeyTrigger.key(KeyCode.F4), KeyActions.PROJECT_EXPAND_ALL);
        builder.bind(KeyTrigger.alt('c'), KeyActions.PROJECT_COLLAPSE_ALL);
        builder.bind(KeyTrigger.alt('v'), KeyActions.PROJECT_VIEW_MENU);
        builder.bind(KeyTrigger.alt('n'), KeyActions.PROJECT_NEW_MENU);
        builder.bind(KeyTrigger.alt('o'), KeyActions.PROJECT_OPTIONS_MENU);
        builder.bind(KeyTrigger.alt('p'), KeyActions.PROJECT_HIDE);
        builder.bind(KeyTrigger.key(KeyCode.ENTER), KeyActions.OPEN_FILE);
        builder.bind(KeyTrigger.ctrl('s'), KeyActions.SAVE);
        bindPlainCtrl(builder, keyboardMode, 'z', KeyActions.UNDO);
        builder.bind(KeyTrigger.ctrl('y'), KeyActions.REDO);
        builder.bind(KeyTrigger.ctrl('x'), KeyActions.CUT);
        builder.bind(KeyTrigger.ctrl('c'), KeyActions.COPY);
        builder.bind(KeyTrigger.ctrl('v'), KeyActions.PASTE);
        bindPlainCtrl(builder, keyboardMode, 'f', KeyActions.FIND);
        builder.bind(KeyTrigger.alt('i'), KeyActions.TOGGLE_IMPORT_FOLD);
        builder.bind(KeyTrigger.ctrl('o'), KeyActions.GO_TO_FILE);
        builder.bind(KeyTrigger.alt('f'), KeyActions.FIND_IN_FILES);
        builder.bind(KeyTrigger.key(KeyCode.ENTER, false, true, false), KeyActions.QUICK_FIX);
        builder.bind(KeyTrigger.alt('='), KeyActions.PROJECT_WIDER);
        builder.bind(KeyTrigger.alt('-'), KeyActions.PROJECT_NARROWER);
        builder.bind(KeyTrigger.alt('.'), KeyActions.BOTTOM_TALLER);
        builder.bind(KeyTrigger.alt(','), KeyActions.BOTTOM_SHORTER);
        builder.bind(KeyTrigger.ctrl('t'), bindingName(KeyActions.SWITCH_THEME));
        builder.bind(KeyTrigger.alt('1'), KeyActions.ACTIVATE_PROJECT);
        bindProfileChords(builder, profile);
        if (keyboardMode == KeyboardMode.AUTO) {
            bindCtrlShiftChords(builder, profile);
        }
        for (ToolWindow toolWindow : toolWindows) {
            String activation = activateToolWindow(toolWindow.id());
            builder.bind(KeyTrigger.alt(toolWindow.shortcut()), activation);
            builder.bind(KeyTrigger.ch(toolWindow.shortcut()), activation);
            toolWindow.extraChord().ifPresent(chord -> builder.bind(chord, activation));
        }
        return builder.build();
    }

    /**
     * Binds a plain Ctrl+letter chord. Under {@link KeyboardMode#AUTO} the chord refuses Shift, because the
     * protocol sends Ctrl+Shift+letter as a distinct chord and that one has its own action (Ctrl+Shift+Z is
     * Redo, Ctrl+Shift+F is Find in Files), so Ctrl+Shift+letter never runs the plain action.
     */
    private static void bindPlainCtrl(Bindings.Builder builder, KeyboardMode keyboardMode, char letter,
            String action) {
        KeyTrigger trigger = KeyTrigger.ctrl(letter);
        builder.bind(keyboardMode == KeyboardMode.AUTO ? new UnshiftedKeyTrigger(trigger) : trigger, action);
    }

    /**
     * Ctrl+Shift chords of the {@code intellij} profile, which the Kitty keyboard protocol delivers as chords
     * of their own. The IntelliJ chords are added on top of the existing fallbacks (Ctrl+O, Alt+F, Ctrl+Y, F4,
     * Alt+C), so a terminal without the protocol still reaches every action.
     */
    private static void bindCtrlShiftChords(Bindings.Builder builder, KeymapProfile profile) {
        switch (profile) {
            case INTELLIJ -> {
                builder.bind(new ShiftedKeyTrigger(KeyTrigger.ctrl('n')), KeyActions.GO_TO_FILE);
                builder.bind(new ShiftedKeyTrigger(KeyTrigger.ctrl('f')), KeyActions.FIND_IN_FILES);
                builder.bind(new ShiftedKeyTrigger(KeyTrigger.ctrl('z')), KeyActions.REDO);
                builder.bind(KeyTrigger.key(KeyCode.KP_ADD, true, false, true), KeyActions.PROJECT_EXPAND_ALL);
                builder.bind(KeyTrigger.key(KeyCode.KP_SUBTRACT, true, false, true),
                        KeyActions.PROJECT_COLLAPSE_ALL);
            }
            case VIM -> {
            }
        }
    }

    /**
     * Chords of the actions that the {@code intellij} profile aligns with the IntelliJ default keymap,
     * and the chords the {@code vim} profile keeps for the same actions. A bare key trigger matches
     * with or without Shift, so the next-problem chords refuse Shift and {@code Shift+F2} and
     * {@code Shift+F8} reach the previous-problem action.
     */
    private static void bindProfileChords(Bindings.Builder builder, KeymapProfile profile) {
        switch (profile) {
            case INTELLIJ -> {
                builder.bind(new UnshiftedKeyTrigger(KeyTrigger.key(KeyCode.F2)), KeyActions.NEXT_PROBLEM);
                builder.bind(KeyTrigger.key(KeyCode.F2, false, false, true), KeyActions.PREVIOUS_PROBLEM);
                builder.bind(KeyTrigger.key(KeyCode.F6, false, false, true), KeyActions.PROJECT_RENAME);
                builder.bind(KeyTrigger.key(KeyCode.F1, false, true, false), KeyActions.PROJECT_SELECT_OPENED_FILE);
                builder.bind(KeyTrigger.key(KeyCode.F4, true, false, false), KeyActions.CLOSE_TAB);
                builder.bind(KeyTrigger.key(KeyCode.RIGHT, false, true, false), KeyActions.NEXT_TAB);
                builder.bind(KeyTrigger.key(KeyCode.LEFT, false, true, false), KeyActions.PREVIOUS_TAB);
                builder.bind(KeyTrigger.ctrl('b'), KeyActions.DEFINITION);
                builder.bind(KeyTrigger.key(KeyCode.F8, true, false, false), KeyActions.TOGGLE_BREAKPOINT);
                builder.bind(KeyTrigger.key(KeyCode.F8, false, true, false), KeyActions.EDIT_BREAKPOINT_CONDITION);
                builder.bind(KeyTrigger.key(KeyCode.F7, false, true, false), KeyActions.USAGES);
                builder.bind(KeyTrigger.ctrl('d'), KeyActions.DUPLICATE_LINE);
                builder.bind(KeyTrigger.ctrl('g'), KeyActions.GO_TO_LINE);
                builder.bind(KeyTrigger.ctrl('a'), KeyActions.SELECT_ALL);
            }
            case VIM -> {
                builder.bind(KeyTrigger.key(KeyCode.F3), KeyActions.PROJECT_SELECT_OPENED_FILE);
                builder.bind(KeyTrigger.key(KeyCode.F2), KeyActions.PROJECT_RENAME);
                builder.bind(KeyTrigger.ctrl('w'), KeyActions.CLOSE_TAB);
                builder.bind(KeyTrigger.alt(']'), KeyActions.NEXT_TAB);
                builder.bind(KeyTrigger.alt('['), KeyActions.PREVIOUS_TAB);
                builder.bind(KeyTrigger.key(KeyCode.F12), KeyActions.DEFINITION);
                builder.bind(KeyTrigger.alt('u'), KeyActions.USAGES);
                builder.bind(new UnshiftedKeyTrigger(KeyTrigger.key(KeyCode.F8)), KeyActions.NEXT_PROBLEM);
                builder.bind(KeyTrigger.key(KeyCode.F8, false, false, true), KeyActions.PREVIOUS_PROBLEM);
                builder.bind(KeyTrigger.ctrl('b'), KeyActions.TOGGLE_BREAKPOINT);
                builder.bind(KeyTrigger.alt('g'), KeyActions.GO_TO_LINE);
                builder.bind(KeyTrigger.ctrl('e'), KeyActions.SELECT_ALL);
            }
        }
    }

    private static List<KeyAction> buildCatalog(List<ToolWindow> toolWindows) {
        List<KeyAction> catalog = new ArrayList<>();
        catalog.add(new KeyAction(QUIT, "Quit"));
        catalog.add(new KeyAction(MOVE_UP, "Move Selection Up"));
        catalog.add(new KeyAction(MOVE_DOWN, "Move Selection Down"));
        catalog.add(new KeyAction(SEARCH_EVERYWHERE, "Search Everywhere"));
        catalog.add(new KeyAction(KEYMAP_HELP, "Keymap Help"));
        catalog.addAll(KeyActions.shellActions());
        catalog.addAll(KeyActions.projectActions());
        for (ToolWindow toolWindow : toolWindows) {
            catalog.add(new KeyAction(activateToolWindow(toolWindow.id()),
                    "Activate " + toolWindow.title() + " Tool Window"));
        }
        catalog.sort(Comparator.comparing(KeyAction::label).thenComparing(KeyAction::id));
        return catalog;
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
            Set<String> reservedIds, KeyboardMode keyboardMode) {
        byte[] content;
        try {
            content = Files.readAllBytes(userKeymapFile);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read user keymap file '" + userKeymapFile + "'", e);
        }
        Properties properties = new Properties();
        try {
            properties.load(new ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new IllegalStateException("cannot parse user keymap file '" + userKeymapFile + "'", e);
        } catch (IllegalArgumentException e) {
            int line = firstMalformedUnicodeEscapeLine(content);
            String location = line > 0 ? " (line " + line + ")" : "";
            throw new IllegalStateException("cannot parse user keymap file '" + userKeymapFile + "'"
                    + location + ": " + e.getMessage(), e);
        }
        Map<String, Integer> lineNumbers = lineNumbersOfKeys(content);
        for (String action : properties.stringPropertyNames()) {
            if (!knownIds.contains(action)) {
                throw new IllegalStateException("unknown action '" + action + "' in user keymap file '"
                        + userKeymapFile + "' (line " + lineNumbers.getOrDefault(action, 0)
                        + "); known actions: " + String.join(", ", new TreeSet<>(knownIds)));
            }
        }
        try {
            if (keyboardMode == KeyboardMode.LEGACY) {
                validateLegacyDeliverable(lineNumbers, properties);
            }
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

    private static void validateLegacyDeliverable(Map<String, Integer> lineNumbers, Properties properties) {
        for (String action : new TreeSet<>(properties.stringPropertyNames())) {
            for (String trigger : properties.getProperty(action).split(",")) {
                String chord = trigger.trim();
                if (!deliverableInLegacyProtocol(chord)) {
                    throw new IllegalArgumentException("trigger '" + chord + "' of action '" + action
                            + "' (line " + lineNumbers.getOrDefault(action, 0) + ") cannot be sent by the legacy "
                            + "keyboard protocol the shell runs; Super, Hyper and Meta modifiers, keypad keys and "
                            + "F13 to F35 need the Kitty keyboard protocol");
                }
            }
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

    /**
     * Physical line of the first malformed backslash-u escape in the given
     * keymap content, or {@code -1} when no line can be named with confidence.
     * Comment lines are skipped the way {@link Properties} skips them. An
     * escape cut off by a line continuation is not reported, because the
     * joined line may well be valid; the caller then names only the file.
     */
    private static int firstMalformedUnicodeEscapeLine(byte[] content) {
        String[] lines = new String(content, java.nio.charset.StandardCharsets.UTF_8).split("\\r?\\n", -1);
        boolean continuation = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (!continuation) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
                    continue;
                }
            }
            boolean continues = endsWithUnescapedBackslash(line);
            String scanned = continues ? line.substring(0, line.length() - 1) : line;
            if (malformedUnicodeEscapeIndex(scanned, continues) >= 0) {
                return index + 1;
            }
            continuation = continues;
        }
        return -1;
    }

    /**
     * Whether the line ends in a single backslash, the marker that joins the
     * next physical line into the same logical line.
     */
    private static boolean endsWithUnescapedBackslash(String line) {
        int backslashes = 0;
        for (int index = line.length() - 1; index >= 0 && line.charAt(index) == '\\'; index--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    /**
     * Index of the first malformed backslash-u escape in the line, or
     * {@code -1}. A truncated escape is only reported when the line does not
     * continue, otherwise the missing digits may sit on the next line.
     */
    private static int malformedUnicodeEscapeIndex(String line, boolean continues) {
        int index = 0;
        while (index < line.length()) {
            if (line.charAt(index) != '\\') {
                index++;
                continue;
            }
            if (index + 1 >= line.length()) {
                return -1;
            }
            if (line.charAt(index + 1) != 'u') {
                index += 2;
                continue;
            }
            if (index + 5 >= line.length()) {
                return continues ? -1 : index;
            }
            for (int digit = 0; digit < 4; digit++) {
                if (Character.digit(line.charAt(index + 2 + digit), 16) < 0) {
                    return index;
                }
            }
            index += 6;
        }
        return -1;
    }

    private static void validateReservedChords(Path userKeymapFile, Map<String, Integer> lineNumbers,
            Bindings layered, Set<String> mentioned, Set<String> reservedIds) {
        for (String action : new TreeSet<>(mentioned)) {
            boolean quit = QUIT.equals(action);
            if (!quit && !reservedIds.contains(action)) {
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
            if (quit && !barePrintable.isEmpty()) {
                throw new IllegalStateException("the action 'quit' in user keymap file '" + userKeymapFile
                        + "' (line " + lineNumbers.getOrDefault(action, 0)
                        + ") must not be reached by a bare printable character, it would quit the shell while text is "
                        + "typed into the palette filter or a capturing window");
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
                    if (other.equals(action) || sharesCtrlC(action, other)) {
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
     * Ctrl+C is quit everywhere and copy inside an editor selection. The editor takes it only while
     * text is selected, so the pair is the one chord both actions may share.
     */
    private static boolean sharesCtrlC(String action, String other) {
        return Set.of(QUIT, KeyActions.COPY).equals(Set.of(action, other));
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
     * Keyboard mode the keymap was built for. The shell runner is configured with the same mode, so the
     * bindings and the bytes the terminal sends agree.
     */
    public KeyboardMode keyboardMode() {
        return keyboardMode;
    }

    /**
     * TamboUI keyboard protocol of {@link #keyboardMode()}, the value the runner is configured with.
     */
    public KeyboardProtocol keyboardProtocol() {
        return keyboardMode.protocol();
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
        return matchesMainKeyAware(bindings, event, bindingName(actionId));
    }

    /**
     * Whether the event triggers the action through the reserved chords only,
     * the chords the shell honors while a tool window captures the input:
     * Ctrl/Alt/F-key chords, never bare printable characters.
     */
    public boolean matchesReserved(KeyEvent event, String actionId) {
        return matchesMainKeyAware(reserved, event, bindingName(actionId));
    }

    private static boolean matchesMainKeyAware(Bindings source, KeyEvent event, String binding) {
        if (source.matches(event, binding)) {
            return true;
        }
        KeyEvent mainKey = Keys.mainKey(event);
        return mainKey != event && source.matches(mainKey, binding);
    }

    /**
     * Whether the action has at least one chord in this keymap. An action that
     * the user file leaves without triggers has none.
     */
    public boolean isBound(String actionId) {
        return !bindings.triggersFor(bindingName(actionId)).isEmpty();
    }

    /**
     * Human readable description of the shortcuts bound to the action, for
     * example {@code Ctrl+P}; used by the palette and the help overlay. Empty
     * when the user file left the action without triggers. Keypad twins of a
     * main key ({@code KP_DOWN} next to {@code Down}) are left out, unless the
     * action has no other trigger.
     */
    public String describeBindings(String actionId) {
        List<InputTrigger> shown = bindings.triggersFor(bindingName(actionId)).stream()
                .filter(trigger -> !isKeypadTwin(trigger))
                .toList();
        if (shown.isEmpty()) {
            return bindings.describeBindings(bindingName(actionId));
        }
        return String.join(", ", shown.stream().map(InputTrigger::describe).toList());
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
