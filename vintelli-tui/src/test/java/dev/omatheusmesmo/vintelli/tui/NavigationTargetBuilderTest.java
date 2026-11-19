package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.NavigationCapability.Location;
import dev.omatheusmesmo.vintelli.tui.navigation.NavigationTarget;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavigationTargetBuilderTest {

    private static final Path ROOT = Path.of("/project");
    private static final Path FILE = ROOT.resolve("src/app/Service.java");

    @Test
    void aPreviewIsTheTrimmedLineAndTheSymbolRangeFollowsTheTrim() {
        NavigationTargetBuilder builder = builder(Map.of(FILE, "class Service {\r\n\t\tint total = helper(1);\r\n}\r\n"));

        NavigationTarget target = builder.build(List.of(new Location(FILE, 1, 14)), "helper").getFirst();

        assertEquals("int total = helper(1);", target.preview());
        assertEquals(12, target.matchStart());
        assertEquals(6, target.matchLength());
        assertEquals("Service.java", target.name());
        assertEquals("src/app", target.directory());
        assertEquals("2:15", target.position());
    }

    @Test
    void aPreviewIsCappedAndTheRangeBeyondTheCapIsDropped() {
        String line = "x".repeat(250) + "helper";
        NavigationTargetBuilder builder = builder(Map.of(FILE, line));

        NavigationTarget target = builder.build(List.of(new Location(FILE, 0, 250)), "helper").getFirst();

        assertEquals(NavigationTargetBuilder.PREVIEW_LIMIT, target.preview().length());
        assertEquals(-1, target.matchStart());
    }

    @Test
    void aRangeThatCrossesTheCapIsClippedToIt() {
        String line = "y".repeat(197) + "helper";
        NavigationTargetBuilder builder = builder(Map.of(FILE, line));

        NavigationTarget target = builder.build(List.of(new Location(FILE, 0, 197)), "helper").getFirst();

        assertEquals(197, target.matchStart());
        assertEquals(3, target.matchLength());
    }

    @Test
    void aSymbolThatIsNotAtTheColumnIsNotMarked() {
        NavigationTargetBuilder builder = builder(Map.of(FILE, "int total = other(1);\n"));

        NavigationTarget target = builder.build(List.of(new Location(FILE, 0, 12)), "helper").getFirst();

        assertEquals("int total = other(1);", target.preview());
        assertEquals(-1, target.matchStart());
    }

    @Test
    void anUnreadableFileAndALineOutsideItStillListTheirRowsWithoutAPreview() {
        Path missing = ROOT.resolve("Gone.java");
        NavigationTargetBuilder builder = builder(Map.of(FILE, "one line\n"));

        List<NavigationTarget> targets = builder.build(
                List.of(new Location(missing, 4, 2), new Location(FILE, 9, 0)), "helper");

        assertEquals(List.of("", ""), targets.stream().map(NavigationTarget::preview).toList());
        assertEquals("Gone.java", targets.getFirst().name());
        assertEquals("", targets.getFirst().directory(), "a file in the project root has no directory");
    }

    @Test
    void aFileIsReadOnceForAllItsLocations() {
        AtomicInteger reads = new AtomicInteger();
        NavigationTargetBuilder builder = new NavigationTargetBuilder(ROOT, file -> {
            reads.incrementAndGet();
            return Optional.of("a\nb\nc\n");
        });

        builder.build(List.of(new Location(FILE, 0, 0), new Location(FILE, 1, 0), new Location(FILE, 2, 0)), "a");

        assertEquals(1, reads.get());
    }

    @Test
    void aFileOutsideTheProjectShowsItsAbsoluteDirectory() {
        Path outside = Path.of("/elsewhere/lib/Dep.java");
        NavigationTargetBuilder builder = builder(Map.of());

        NavigationTarget target = builder.build(List.of(new Location(outside, 0, 0)), "Dep").getFirst();

        assertTrue(target.directory().endsWith("elsewhere/lib"), target.directory());
    }

    private static NavigationTargetBuilder builder(Map<Path, String> files) {
        return new NavigationTargetBuilder(ROOT, file -> Optional.ofNullable(files.get(file)));
    }
}
