package dev.omatheusmesmo.vintelli.tui.icons;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.omatheusmesmo.vintelli.tui.keymap.ConfigDirectory;
import dev.tamboui.style.Color;

/**
 * File type icons of the TUI, resolved by file name and by extension from a
 * mapping resource instead of code. Given a file or folder name, the component
 * answers the {@link FileIcon} of the resolved {@link IconMode}: a Nerd Font
 * glyph when the session runs with a patched font (chosen by the
 * {@code vintelli.icons} configuration and, on {@code auto}, by the terminal
 * capability), or an ASCII marker plus color otherwise.
 *
 * <p>The mapping lives in
 * {@code classpath:dev/omatheusmesmo/vintelli/tui/icons/file-icons.properties}
 * with the keys {@code default.file.*}, {@code default.folder.*},
 * {@code name.<file name>.*} (exact name match, files and folders, for example
 * {@code name.pom.xml.glyph}) and {@code ext.<extension>.*}, each defining
 * {@code glyph}, {@code ascii} and an optional {@code color} (ANSI color name
 * such as {@code red} or {@code light-blue}). A user file
 * {@code <config>/vintelli/file-icons.properties}, where the config directory is
 * resolved by {@link ConfigDirectory} from {@code XDG_CONFIG_HOME} (absolute),
 * then {@code APPDATA}, then {@code user.home/.config}, is layered on top in
 * the same key format: any key of the user file overrides exactly that field.
 * A missing default user file is absent; the {@code vintelli.icons.file}
 * system property names an explicit user file, which must exist.
 *
 * <p>Lookup order for an ordinary file: exact name match first, then the
 * extension (after the last dot, case-insensitive), then the
 * {@code default.file} icon. Folders check the exact name match and fall back
 * to {@code default.folder}. Names and extensions compare in lowercase. A
 * file name is taken from its last path segment, so callers may pass whole
 * relative or absolute paths or bare names.
 *
 * <p>Invalid mapping data fails fast: an unknown key grammar, a duplicate key
 * of one source, a field with an unknown color name or an entry missing its
 * glyph or ASCII marker throws {@link IllegalArgumentException} naming the
 * source file that defines the offending key. The built-in resource is
 * validated the same way during the load, so map drift is caught by tests
 * before it reaches a user.
 */
public final class FileIcons {

    static final String RESOURCE = "dev/omatheusmesmo/vintelli/tui/icons/file-icons.properties";
    static final String USER_FILE_PROPERTY = "vintelli.icons.file";

    private static final String RESOURCE_SOURCE = "resource '" + RESOURCE + "'";
    private static final Pattern KEY_PATTERN =
            Pattern.compile("^(name|ext|default)\\.(.*)\\.(glyph|ascii|color)$");
    private static final Map<String, Color> COLOR_NAMES = buildColorNames();

    private final IconMode mode;
    private final Map<String, FileIcon> byName;
    private final Map<String, FileIcon> byExtension;
    private final FileIcon defaultFile;
    private final FileIcon defaultFolder;

    private FileIcons(IconMode mode, Map<String, FileIcon> byName, Map<String, FileIcon> byExtension,
            FileIcon defaultFile, FileIcon defaultFolder) {
        this.mode = mode;
        this.byName = byName;
        this.byExtension = byExtension;
        this.defaultFile = defaultFile;
        this.defaultFolder = defaultFolder;
    }

    /**
     * The icons of a running session: mode from the {@code vintelli.icons}
     * property and the terminal environment, built-in mapping resource under
     * the user mapping file. An explicit {@code vintelli.icons.file} that does
     * not exist fails fast.
     */
    public static FileIcons resolve() {
        return of(System.getProperty(IconMode.CONFIG_PROPERTY, "auto"), System.getenv(), userMappingFile());
    }

    /**
     * The icons for an explicit configuration, terminal environment and
     * optional user mapping file; the test seam of {@link #resolve()}. A user
     * mapping file that does not exist leaves the built-in mapping in place.
     */
    static FileIcons of(String configValue, Map<String, String> env, Path userMappingFile) {
        IconMode mode = IconMode.fromConfig(configValue).resolve(env);
        Map<String, Map<String, Setting>> groups = new LinkedHashMap<>();
        collect(loadResource(RESOURCE), RESOURCE_SOURCE, groups);
        if (userMappingFile != null && Files.isRegularFile(userMappingFile)) {
            collect(loadUserFile(userMappingFile), "'" + userMappingFile + "'", groups);
        }
        return parse(mode, groups);
    }

    private static void collect(Properties properties, String source, Map<String, Map<String, Setting>> groups) {
        for (String declared : properties.stringPropertyNames()) {
            Matcher matcher = KEY_PATTERN.matcher(declared.toLowerCase(Locale.ROOT));
            if (!matcher.matches()) {
                throw new IllegalArgumentException("invalid icon mapping key '" + declared + "' in " + source
                        + "; expected 'default.{file|folder}.{glyph|ascii|color}', 'name.<file name>.{...}' or "
                        + "'ext.<extension>.{...}'");
            }
            Map<String, Setting> group = groups.computeIfAbsent(matcher.group(1) + ":" + matcher.group(2),
                    key -> new LinkedHashMap<>());
            Setting previous = group.put(matcher.group(3), new Setting(properties.getProperty(declared), source));
            if (previous != null && previous.source().equals(source)) {
                throw new IllegalArgumentException("duplicate icon mapping key '" + declared + "' in " + source);
            }
        }
    }

    private static FileIcons parse(IconMode mode, Map<String, Map<String, Setting>> groups) {
        Map<String, FileIcon> byName = new HashMap<>();
        Map<String, FileIcon> byExtension = new HashMap<>();
        Map<String, FileIcon> defaults = new HashMap<>();
        for (Map.Entry<String, Map<String, Setting>> group : groups.entrySet()) {
            String id = group.getKey();
            String scope = id.substring(0, id.indexOf(':'));
            String base = id.substring(id.indexOf(':') + 1);
            Map<String, Setting> values = group.getValue();
            String origin = values.values().iterator().next().source();
            String label = "key '" + scope + "." + base + ".*'";
            String glyph = requireField("glyph", values, label + " in " + origin);
            String ascii = requireField("ascii", values, label + " in " + origin);
            FileIcon icon = new FileIcon(glyph, ascii, color(values.get("color"), label));
            switch (scope) {
                case "name" -> byName.put(base.toLowerCase(Locale.ROOT), icon);
                case "ext" -> byExtension.put(base.toLowerCase(Locale.ROOT), icon);
                default -> {
                    if (!base.equals("file") && !base.equals("folder")) {
                        throw new IllegalArgumentException("invalid default '" + base + "' in " + origin
                                + "; expected default.file or default.folder");
                    }
                    defaults.put(base, icon);
                }
            }
        }
        FileIcon file = defaults.get("file");
        FileIcon folder = defaults.get("folder");
        if (file == null || folder == null) {
            throw new IllegalArgumentException("missing default." + (file == null ? "file" : "folder")
                    + " mapping in " + RESOURCE_SOURCE);
        }
        return new FileIcons(mode, Map.copyOf(byName), Map.copyOf(byExtension), file, folder);
    }

    private static String requireField(String field, Map<String, Setting> values, String prefix) {
        Setting setting = values.get(field);
        if (setting == null || setting.value().isBlank()) {
            throw new IllegalArgumentException(prefix + ": missing '" + field + "' value");
        }
        return setting.value();
    }

    private static Color color(Setting setting, String label) {
        if (setting == null || setting.value().isBlank()) {
            return null;
        }
        Color color = COLOR_NAMES.get(setting.value().trim().toLowerCase(Locale.ROOT));
        if (color == null) {
            throw new IllegalArgumentException(label + " in " + setting.source() + ": unknown color name '"
                    + setting.value() + "'");
        }
        return color;
    }

    private static Map<String, Color> buildColorNames() {
        Map<String, Color> names = new LinkedHashMap<>();
        names.put("black", Color.BLACK);
        names.put("red", Color.RED);
        names.put("green", Color.GREEN);
        names.put("yellow", Color.YELLOW);
        names.put("blue", Color.BLUE);
        names.put("magenta", Color.MAGENTA);
        names.put("cyan", Color.CYAN);
        names.put("white", Color.WHITE);
        names.put("gray", Color.GRAY);
        names.put("dark-gray", Color.DARK_GRAY);
        names.put("light-red", Color.LIGHT_RED);
        names.put("light-green", Color.LIGHT_GREEN);
        names.put("light-yellow", Color.LIGHT_YELLOW);
        names.put("light-blue", Color.LIGHT_BLUE);
        names.put("light-magenta", Color.LIGHT_MAGENTA);
        names.put("light-cyan", Color.LIGHT_CYAN);
        names.put("bright-white", Color.BRIGHT_WHITE);
        return Map.copyOf(names);
    }

    private static Properties loadResource(String resource) {
        try (InputStream in = FileIcons.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing icon mapping resource '" + resource + "'");
            }
            return loadProperties(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read icon mapping resource '" + resource + "'", e);
        }
    }

    private static Properties loadUserFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return loadProperties(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read icon mapping file '" + file + "'", e);
        }
    }

    private static Properties loadProperties(InputStream in) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            Properties properties = new Properties();
            properties.load(reader);
            return properties;
        }
    }

    /**
     * The user mapping file of the session: the explicit {@code vintelli.icons.file}
     * property when set (it must exist), otherwise {@code vintelli/file-icons.properties}
     * inside the resolved config directory, or null when no directory can be
     * derived.
     */
    private static Path userMappingFile() {
        String override = System.getProperty(USER_FILE_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path explicit = Path.of(override);
            if (!Files.isRegularFile(explicit)) {
                throw new IllegalStateException("icon mapping file '" + explicit + "' named by "
                        + USER_FILE_PROPERTY + " does not exist");
            }
            return explicit;
        }
        Path configRoot = ConfigDirectory.configRoot(null, System.getenv("XDG_CONFIG_HOME"),
                System.getenv("APPDATA"), System.getProperty("user.home"));
        return configRoot == null ? null : configRoot.resolve("vintelli").resolve("file-icons.properties");
    }

    /**
     * The resolved face of the component; every lookup answers symbols of this
     * face.
     */
    public IconMode mode() {
        return mode;
    }

    /**
     * Icon of an ordinary file: exact name match first, then extension, then
     * the default file icon.
     */
    public FileIcon iconFor(String fileName) {
        String name = lastSegment(fileName);
        FileIcon byName = this.byName.get(name.toLowerCase(Locale.ROOT));
        if (byName != null) {
            return byName;
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            FileIcon byExtension = this.byExtension.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (byExtension != null) {
                return byExtension;
            }
        }
        return defaultFile;
    }

    /**
     * Icon of a folder: exact name match first, then the default folder icon.
     */
    public FileIcon folderIconFor(String folderName) {
        FileIcon byName = this.byName.get(lastSegment(folderName).toLowerCase(Locale.ROOT));
        return byName != null ? byName : defaultFolder;
    }

    private static String lastSegment(String fileName) {
        if (fileName == null) {
            return "";
        }
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        return slash >= 0 ? fileName.substring(slash + 1) : fileName;
    }

    Map<String, FileIcon> nameEntries() {
        return byName;
    }

    Map<String, FileIcon> extensionEntries() {
        return byExtension;
    }

    private record Setting(String value, String source) {
    }
}
