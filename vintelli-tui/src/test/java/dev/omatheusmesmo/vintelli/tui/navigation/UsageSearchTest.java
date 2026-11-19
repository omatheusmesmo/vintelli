package dev.omatheusmesmo.vintelli.tui.navigation;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UsageSearchTest {

    @Test
    void theUsagesAreGroupedByFileInTheOrderOfTheAnswerAndTheHeaderCountsFilesAndUsages() {
        UsageSearch search = new UsageSearch("helper", List.of(usage("A.java", 1), usage("B.java", 4),
                usage("A.java", 9), usage("C.java", 2)));

        List<UsageSearch.FileUsages> files = search.files();

        assertEquals(List.of("A.java", "B.java", "C.java"), files.stream().map(UsageSearch.FileUsages::name).toList());
        assertEquals(List.of(1, 9), files.getFirst().usages().stream().map(NavigationTarget::line).toList());
        assertEquals("Usages of helper in 3 files (4)", search.header());
    }

    @Test
    void aSingleFileReadsInTheSingular() {
        assertEquals("Usages of run in 1 file (2)",
                new UsageSearch("run", List.of(usage("A.java", 1), usage("A.java", 2))).header());
    }

    private static NavigationTarget usage(String name, int line) {
        return new NavigationTarget(Path.of("/project").resolve(name), name, "", line, 0, "x", -1, -1);
    }
}
