package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.Location;
import dev.omatheusmesmo.vintelli.tui.navigation.NavigationTarget;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Turns the locations a navigation request answered into the rows the popup and the Find tool window list: the file
 * name, its directory relative to the project, and the trimmed source line with the symbol marked. A file is read once
 * per build, from the open document when the file is open and otherwise through the source the editor was given, and a
 * file that cannot be read still lists its rows, without a preview.
 */
final class NavigationTargetBuilder {

    static final int PREVIEW_LIMIT = 200;

    private final Path projectRoot;
    private final Function<Path, Optional<String>> source;

    NavigationTargetBuilder(Path projectRoot, Function<Path, Optional<String>> source) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.source = source;
    }

    List<NavigationTarget> build(List<Location> locations, String symbol) {
        Map<Path, List<String>> lines = new HashMap<>();
        return locations.stream().map(location -> target(location, symbol,
                lines.computeIfAbsent(location.filePath(), this::linesOf))).toList();
    }

    private List<String> linesOf(Path file) {
        return source.apply(file).map(text -> text.lines().toList()).orElse(List.of());
    }

    private NavigationTarget target(Location location, String symbol, List<String> lines) {
        String raw = location.line() >= 0 && location.line() < lines.size() ? lines.get(location.line()) : "";
        String stripped = raw.strip();
        int leading = raw.length() - raw.stripLeading().length();
        String preview = stripped.length() > PREVIEW_LIMIT ? stripped.substring(0, PREVIEW_LIMIT) : stripped;
        int start = location.column() - leading;
        boolean marked = !symbol.isEmpty() && start >= 0 && start < preview.length()
                && raw.startsWith(symbol, location.column());
        Path file = location.filePath();
        return new NavigationTarget(file, String.valueOf(file.getFileName()), directoryOf(file), location.line(),
                location.column(), preview, marked ? start : -1,
                marked ? Math.min(symbol.length(), preview.length() - start) : -1);
    }

    private String directoryOf(Path file) {
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return "";
        }
        Path shown = parent.startsWith(projectRoot) ? projectRoot.relativize(parent) : parent;
        return shown.toString().replace('\\', '/');
    }
}
