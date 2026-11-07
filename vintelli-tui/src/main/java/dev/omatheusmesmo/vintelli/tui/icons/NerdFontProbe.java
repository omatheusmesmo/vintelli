package dev.omatheusmesmo.vintelli.tui.icons;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Looks for an installed Nerd Font file in the font directories of the platform.
 *
 * <p>The walk is bounded: a file counts when it sits at most {@link #MAX_DEPTH}
 * levels below a font directory, so {@code fonts/a/b/c/x.ttf} is found and
 * {@code fonts/a/b/c/d/x.ttf} is not. A missing or unreadable directory, or any
 * I/O failure while walking one, counts as no font in that directory only, so
 * the other directories are still searched. The probe never throws.
 */
final class NerdFontProbe {

    static final int MAX_DEPTH = 4;

    private static final Set<String> FONT_EXTENSIONS = Set.of("ttf", "otf", "ttc", "woff2");
    private static final Set<String> NAME_MARKERS = Set.of("nerdfont", "nerd font", "nerd-font");
    private static final Pattern SHORT_FORM = Pattern.compile("(NF|NFM|NFP)[-.]");

    private NerdFontProbe() {
    }

    static boolean installed(Map<String, String> env, String osName, String home) {
        return containsNerdFont(fontDirectories(env, osName, home));
    }

    static List<Path> fontDirectories(Map<String, String> env, String osName, String home) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        Set<Path> directories = new LinkedHashSet<>();
        if (os.startsWith("mac")) {
            addUnder(directories, home, "Library", "Fonts");
            addUnder(directories, "/Library/Fonts");
        } else if (os.startsWith("windows")) {
            addUnder(directories, env.get("LOCALAPPDATA"), "Microsoft", "Windows", "Fonts");
            addUnder(directories, env.get("WINDIR"), "Fonts");
        } else {
            addAbsolute(directories, env.get("XDG_DATA_HOME"), "fonts");
            addUnder(directories, home, ".local", "share", "fonts");
            addUnder(directories, home, ".fonts");
            for (String entry : dataDirs(env.get("XDG_DATA_DIRS"))) {
                addAbsolute(directories, entry, "fonts");
            }
            addUnder(directories, "/usr/share/fonts");
            addUnder(directories, "/usr/local/share/fonts");
        }
        return List.copyOf(directories);
    }

    static boolean containsNerdFont(List<Path> directories) {
        for (Path directory : directories) {
            if (containsNerdFont(directory)) {
                return true;
            }
        }
        return false;
    }

    static boolean isNerdFontFile(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || !FONT_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (NAME_MARKERS.stream().anyMatch(lower::contains)) {
            return true;
        }
        return SHORT_FORM.matcher(fileName).find();
    }

    private static boolean containsNerdFont(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (Stream<Path> entries = Files.walk(directory, MAX_DEPTH, FileVisitOption.FOLLOW_LINKS)) {
            return entries.filter(Files::isRegularFile)
                    .anyMatch(file -> isNerdFontFile(file.getFileName().toString()));
        } catch (IOException | UncheckedIOException e) {
            return false;
        }
    }

    private static List<String> dataDirs(String value) {
        return value == null ? List.of() : List.of(value.split(":"));
    }

    private static void addUnder(Set<Path> directories, String base, String... parts) {
        if (base == null || base.isBlank()) {
            return;
        }
        try {
            directories.add(Path.of(base, parts));
        } catch (InvalidPathException ignored) {
        }
    }

    private static void addAbsolute(Set<Path> directories, String base, String... parts) {
        if (base == null || base.isBlank()) {
            return;
        }
        try {
            if (Path.of(base).isAbsolute()) {
                directories.add(Path.of(base, parts));
            }
        } catch (InvalidPathException ignored) {
        }
    }
}
