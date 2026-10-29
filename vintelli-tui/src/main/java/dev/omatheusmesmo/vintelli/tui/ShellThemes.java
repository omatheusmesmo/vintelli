package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.ShellEditorPalette;
import dev.omatheusmesmo.vintelli.tui.keymap.ConfigDirectory;
import dev.tamboui.css.Styleable;
import dev.tamboui.css.cascade.CssStyleResolver;
import dev.tamboui.css.cascade.PseudoClassState;
import dev.tamboui.css.engine.StyleEngine;
import dev.tamboui.css.model.Stylesheet;
import dev.tamboui.css.parser.CssParser;
import dev.tamboui.css.parser.CssParseException;
import dev.tamboui.style.Color;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the shell stylesheets: the two shipped themes and the user theme
 * file, the active stylesheet, the fail fast validation of theme names and
 * the runtime switch. Every color in the shell comes from the stylesheet
 * through the {@link StyleEngine}; nothing resolves colors outside of it.
 *
 * <p>The two shipped themes are classpath stylesheets, one per editor and UI
 * flavor. The user theme file lives in the config directory (default
 * {@code vintelli/themes.tcss}, resolved by {@link ConfigDirectory}, or the
 * path from {@code vintelli.tui.theme.file}): without a {@code @theme name}
 * directive its rules act as a patch appended to every shipped theme; with
 * the directive the file defines a theme of its own and, with
 * {@code @theme name extends base}, extends a shipped theme so a single
 * override does not have to repeat every palette value. A directive naming a
 * shipped theme replaces that theme.
 *
 * <p>The default theme comes from {@code vintelli.tui.theme} (default
 * {@code dark}). An unknown name fails at construction with the available
 * names in the message; the same validation applies at runtime on
 * {@link #switchTheme(String)}. A user theme file that cannot be read or
 * parsed, that carries more than one directive, that extends an unknown base
 * theme or that does not declare the required palette variables fails fast
 * with the file and the detail in the message; no theme registers without a
 * complete palette.
 */
@Singleton
public final class ShellThemes {

    public static final String DEFAULT_THEME = "dark";
    static final String DEFAULT_USER_FILE_NAME = "themes.tcss";
    static final Pattern THEME_DIRECTIVE =
            Pattern.compile("(?m)^\\s*@theme\\s+([A-Za-z0-9_.-]+)(?:\\s+extends\\s+([A-Za-z0-9_.-]+))?\\s*$");
    private static final Pattern DIRECTIVE_LINE = Pattern.compile("(?m)^\\s*@theme[^\\n]*(?:\\n|$)");

    static final List<String> REQUIRED_VARIABLES = List.of(
            "panel",
            "chrome-fg",
            "chrome-fg-strong",
            "muted",
            "soft",
            "selection-bg",
            "selection-fg",
            "tab-selected-bg",
            "tab-selected-fg",
            "border",
            "focus-border",
            "editor-border",
            "editor-bg",
            "editor-fg",
            "editor-keyword",
            "editor-string",
            "editor-comment",
            "editor-number",
            "editor-constant",
            "editor-selection",
            "editor-sel-fg");

    private static final String RESOURCE_DIR = "/dev/omatheusmesmo/vintelli/tui/themes/";
    private static final List<String> SHIPPED_THEMES = List.of("darcula", "dark");

    private final StyleEngine engine = StyleEngine.create();
    private final Map<String, Stylesheet> parsed = new LinkedHashMap<>();
    private final Map<String, String> sources = new LinkedHashMap<>();
    private volatile String activeTheme;

    @Inject
    public ShellThemes(@ConfigProperty(name = "vintelli.tui.theme", defaultValue = DEFAULT_THEME) String configuredTheme,
                       @ConfigProperty(name = "vintelli.tui.theme.file") Optional<String> configuredUserFile,
                       @ConfigProperty(name = "vintelli.config.dir") Optional<String> configuredConfigDir) {
        this(configuredTheme, userThemePath(configuredUserFile, configuredConfigDir));
    }

    public ShellThemes(String configuredTheme, Path userThemeFile) {
        String userCss = userThemeFile == null ? null : readThemeText(userThemeFile);
        UserDirective directive = userCss == null ? null : directiveOf(userThemeFile, userCss);
        if (directive == null) {
            String userPatch = userCss == null ? null : withoutDirectives(userCss);
            if (userPatch != null) {
                ensurePatch(userThemeFile, userPatch);
            }
            for (String name : SHIPPED_THEMES) {
                register(name, subjectFor(name), classpathTheme(name), userPatch);
            }
        } else {
            for (String name : SHIPPED_THEMES) {
                register(name, subjectFor(name), classpathTheme(name), null);
            }
            registerUserTheme(userThemeFile, directive, userCss);
        }
        this.activeTheme = validated(configuredTheme, availableThemeNames());
        engine.setActiveStylesheet(activeTheme);
    }

    /**
     * The engine behind the shell elements; callers attach it to the runner
     * and read resolved styles from it, never from hardcoded constants.
     */
    public StyleEngine engine() {
        return engine;
    }

    public List<String> availableThemeNames() {
        return List.copyOf(sources.keySet());
    }

    public String activeTheme() {
        return activeTheme;
    }

    /**
     * Activates another stylesheet. Unknown names fail with the available
     * names in the message, mirroring the fail fast at startup.
     */
    public void switchTheme(String name) {
        String validated = validated(name, availableThemeNames());
        engine.setActiveStylesheet(validated);
        activeTheme = validated;
    }

    /**
     * Resolves a styled role defined in the stylesheet, such as
     * {@code tool-window} or {@code menu-entry}; element rendering resolves
     * through the engine, this lookup serves the code paths that draw their
     * own widgets and the tests that assert palette values.
     */
    public CssStyleResolver resolver(String role) {
        return engine.resolve(new StyledRole(role), PseudoClassState.NONE, List.of());
    }

    public Color color(String variableName) {
        return Color.hex(variable(variableName));
    }

    /**
     * Editor palette of the active theme, straight from the stylesheet
     * variables; separate from the chrome palette by construction.
     */
    public ShellEditorPalette editorPalette() {
        return new ShellEditorPalette(
                variable("editor-bg"),
                variable("editor-fg"),
                variable("editor-keyword"),
                variable("editor-string"),
                variable("editor-comment"),
                variable("editor-number"),
                variable("editor-constant"),
                variable("editor-selection"),
                variable("editor-sel-fg"));
    }

    public Map<String, String> variablesOf(String themeName) {
        return parsed.get(validated(themeName, availableThemeNames())).variables();
    }

    private String variable(String name) {
        Map<String, String> variables = parsed.get(activeTheme).variables();
        String value = variables.get(name);
        if (value == null) {
            throw new IllegalStateException("theme '" + activeTheme + "' does not declare variable '" + name + "'");
        }
        return value;
    }

    private String validated(String name, Collection<String> themes) {
        if (!themes.contains(name)) {
            throw new IllegalStateException("unknown shell theme '" + name + "'; available themes: " + String.join(", ", themes));
        }
        return name;
    }

    private static String subjectFor(String name) {
        return "theme '" + name + "'";
    }

    private void register(String name, String subject, String shippedCss, String userPatch) {
        String css = userPatch == null ? shippedCss : shippedCss + "\n" + userPatch;
        Stylesheet stylesheet = CssParser.parse(css);
        ensureVariables(subject, stylesheet);
        engine.addStylesheet(name, css);
        sources.put(name, css);
        parsed.put(name, stylesheet);
    }

    private void registerUserTheme(Path userThemeFile, UserDirective directive, String userCss) {
        String css = withoutDirectives(userCss);
        parseUserCss(userThemeFile, css);
        if (directive.base() != null) {
            String base = directive.base();
            if (!SHIPPED_THEMES.contains(base)) {
                throw new IllegalStateException("user theme file '" + userThemeFile + "' extends unknown theme '"
                        + base + "'; available bases: " + String.join(", ", SHIPPED_THEMES));
            }
            css = classpathTheme(base) + "\n" + css;
        }
        register(directive.name(), "user theme file '" + userThemeFile + "'", "", css);
    }

    /**
     * Parses user text on its own, before it is combined with any shipped
     * stylesheet: a broken file is reported with the file and the position,
     * never as a failure of the theme it happens to be appended to.
     */
    private static void ensurePatch(Path userThemeFile, String userCss) {
        ensureSingleDirective(userThemeFile, userCss);
        parseUserCss(userThemeFile, userCss);
    }

    private static void parseUserCss(Path userThemeFile, String css) {
        try {
            CssParser.parse(css);
        } catch (CssParseException failure) {
            throw new IllegalStateException("user theme file '" + userThemeFile
                    + "' is invalid: " + failure.getMessage(), failure);
        }
    }

    private static void ensureSingleDirective(Path userThemeFile, String userCss) {
        Matcher directives = THEME_DIRECTIVE.matcher(userCss);
        int count = 0;
        while (directives.find()) {
            count++;
        }
        if (count > 1) {
            throw new IllegalStateException("user theme file '" + userThemeFile + "' carries " + count
                    + " @theme directives, at most one is allowed");
        }
    }

    private void ensureVariables(String subject, Stylesheet stylesheet) {
        List<String> missing = REQUIRED_VARIABLES.stream()
                .filter(variable -> !stylesheet.variables().containsKey(variable))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(subject + " does not declare variables: " + String.join(", ", missing));
        }
    }

    private UserDirective directiveOf(Path userThemeFile, String userCss) {
        UserDirective directive = null;
        Matcher directives = THEME_DIRECTIVE.matcher(userCss);
        while (directives.find()) {
            if (directive != null) {
                throw new IllegalStateException("user theme file '" + userThemeFile
                        + "' carries more than one @theme directives, at most one is allowed");
            }
            directive = new UserDirective(directives.group(1), directives.group(2));
        }
        return directive;
    }

    private static String classpathTheme(String name) {
        return readResource(RESOURCE_DIR + name + ".tcss");
    }

    private static String readResource(String resource) {
        InputStream stream = ShellThemes.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("shipped theme stylesheet not found: " + resource);
        }
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("shipped theme stylesheet unreadable: " + resource, failure);
        }
    }

    private static String readThemeText(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("user theme file not found: " + path);
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("user theme file unreadable: " + path, failure);
        }
    }

    private static String withoutDirectives(String css) {
        return DIRECTIVE_LINE.matcher(css).replaceAll("");
    }

    private static Path userThemePath(Optional<String> configuredFile, Optional<String> configuredDir) {
        if (configuredFile != null && configuredFile.isPresent() && !configuredFile.get().isBlank()) {
            return Path.of(expand(configuredFile.get()));
        }
        return defaultUserThemeFile(configuredDir).orElse(null);
    }

    private static Optional<Path> defaultUserThemeFile(Optional<String> configuredDir) {
        String configured = configuredDir == null ? null : configuredDir.orElse(null);
        Path root = ConfigDirectory.configRoot(configured,
                System.getenv().get("XDG_CONFIG_HOME"),
                System.getenv().get("APPDATA"),
                System.getProperty("user.home"));
        if (root == null) {
            return Optional.empty();
        }
        Path file = root.resolve("vintelli").resolve(DEFAULT_USER_FILE_NAME);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    private static String expand(String value) {
        if (value.equals("~") || value.startsWith("~/")) {
            return System.getProperty("user.home") + value.substring(1);
        }
        return value;
    }

    private record UserDirective(String name, String base) {
    }

    /**
     * Stand in for shell code paths that resolve a styled role through the
     * engine without a render context; stylesheet rules match the role class.
     */
    private record StyledRole(String role) implements Styleable {

        @Override
        public String styleType() {
            return role;
        }

        @Override
        public Optional<String> cssId() {
            return Optional.empty();
        }

        @Override
        public Set<String> cssClasses() {
            return Set.of(role);
        }

        @Override
        public Optional<Styleable> cssParent() {
            return Optional.empty();
        }
    }
}
