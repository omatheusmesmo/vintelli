package dev.omatheusmesmo.vintelli.tui.navigation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The result of one Find Usages search: the symbol and every usage, in the order the server answered. The Find tool
 * window shows it grouped by file, and keeps it until the next search replaces it.
 */
public record UsageSearch(String symbol, List<NavigationTarget> usages) {

    /** The usages of one file, in the order of the answer. */
    public record FileUsages(Path file, String name, String directory, List<NavigationTarget> usages) {
    }

    public UsageSearch {
        Objects.requireNonNull(symbol, "symbol");
        usages = List.copyOf(usages);
    }

    public List<FileUsages> files() {
        Map<Path, List<NavigationTarget>> byFile = new LinkedHashMap<>();
        for (NavigationTarget usage : usages) {
            byFile.computeIfAbsent(usage.file(), file -> new ArrayList<>()).add(usage);
        }
        List<FileUsages> files = new ArrayList<>();
        byFile.forEach((file, inFile) -> files.add(
                new FileUsages(file, inFile.getFirst().name(), inFile.getFirst().directory(), List.copyOf(inFile))));
        return List.copyOf(files);
    }

    /** The title of the usages: {@code Usages of helper in 4 files (12)}. */
    public String header() {
        int files = files().size();
        return "Usages of " + symbol + " in " + files + (files == 1 ? " file" : " files") + " (" + usages.size() + ")";
    }
}
