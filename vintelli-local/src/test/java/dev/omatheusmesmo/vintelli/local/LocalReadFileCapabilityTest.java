package dev.omatheusmesmo.vintelli.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ReadFileCapability.DirEntry;

/**
 * Confined local filesystem behaviour on real temporary project roots: UTF 8
 * text reads, directory listings sorted like a project tree, and every escape
 * attempt through relative traversal, absolute paths, sibling prefix names or
 * symlinks failing with an explicit reason.
 */
class LocalReadFileCapabilityTest {

    @TempDir
    Path temp;

    private LocalReadFileCapability capability() {
        return new LocalReadFileCapability(new LocalProjectRoot(temp));
    }

    @Test
    void readsUtf8TextThroughARelativeAndAnAbsolutePath() throws IOException {
        Path source = temp.resolve("src").resolve("main");
        Files.createDirectories(source);
        Path file = source.resolve("Main.java");
        Files.writeString(file, "class Main {}", StandardCharsets.UTF_8);

        assertEquals("class Main {}",
                value(capability().readText(temp.relativize(file))));
        assertEquals("class Main {}", value(capability().readText(file)));
    }

    @Test
    void listsDirectoryEntriesFirstDirectoriesThenFilesInNameOrder() throws IOException {
        Files.createDirectories(temp.resolve("src").resolve("main"));
        Files.createDirectories(temp.resolve("docs"));
        Files.writeString(temp.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("src/main/App.java"), "", StandardCharsets.UTF_8);

        List<DirEntry> entries = value(capability().list(temp));

        assertEquals(List.of(
                new DirEntry("docs", true),
                new DirEntry("src", true),
                new DirEntry("pom.xml", false)),
                entries);
    }

    @Test
    void namesAMissingFileInsteadOfReportingAGenericFailure() {
        var result = capability().readText(temp.resolve("no-such-file.txt"));

        assertEquals("The file does not exist",
                assertInstanceOf(CapabilityResult.Failed.class, result).failure().reason());
    }

    @Test
    void refusesBytesThatAreNotTextInsteadOfDecodingGarbage() throws IOException {
        Files.write(temp.resolve("blob.bin"), new byte[] { (byte) 0xD8, (byte) 0x00, (byte) 0xFF });

        assertEquals("The file is not readable as UTF 8 text",
                assertInstanceOf(CapabilityResult.Failed.class, capability().readText(
                        temp.resolve("blob.bin"))).failure().reason());
    }

    @Test
    void refusesRelativeTraversalThatEscapesTheRoot() {
        var result = capability().readText(Path.of("..", "..", "etc", "passwd"));

        assertEquals("The path escapes the project root", failureReasonOf(result));
    }

    @Test
    void refusesAnAbsolutePathOutsideTheRoot() throws IOException {
        Path outsideFile = Files.createTempFile("vintelli-outside-", ".txt");
        try {
            assertEquals("The path escapes the project root",
                    failureReasonOf(capability().readText(outsideFile)));
        } finally {
            Files.deleteIfExists(outsideFile);
        }
    }

    @Test
    void refusesAPathThatSharesOnlyTheNamePrefixOfTheRoot() throws IOException {
        Path sibling = temp.resolveSibling(temp.getFileName() + "-twin");
        try {
            Files.createDirectories(sibling);
            Path twin = sibling.resolve("twin.txt");
            Files.writeString(twin, "twin", StandardCharsets.UTF_8);

            assertEquals("The path escapes the project root",
                    failureReasonOf(capability().readText(twin)));
        } finally {
            deleteTree(sibling);
        }
    }

    @Test
    void readsThroughASymlinkToInsideAndRefusesOneToOutside() throws IOException {
        Path inside = temp.resolve("inside");
        Files.createDirectories(inside);
        Files.writeString(inside.resolve("message.txt"), "hello", StandardCharsets.UTF_8);
        Path outsideFile = Files.createTempFile("vintelli-escape-", ".txt");
        try {
            Files.createSymbolicLink(temp.resolve("home-link"), inside);
            Files.createSymbolicLink(temp.resolve("escape-link"), outsideFile);

            assertEquals("hello",
                    value(capability().readText(temp.resolve("home-link").resolve("message.txt"))));
            assertEquals("The path escapes the project root",
                    failureReasonOf(capability().readText(temp.resolve("escape-link"))));
        } catch (UnsupportedOperationException | FileSystemException e) {
            assumeTrue(false, "This runner cannot create symlinks: " + e);
        } finally {
            Files.deleteIfExists(outsideFile);
        }
    }

    @Test
    void failsListingWhenThePathIsAFileOrDoesNotExist() throws IOException {
        Files.writeString(temp.resolve("plain.txt"), "", StandardCharsets.UTF_8);

        assertEquals("The path is not a directory",
                failureReasonOf(capability().list(temp.resolve("plain.txt"))));
        assertEquals("The path is not a directory",
                failureReasonOf(capability().list(temp.resolve("absent"))));
    }

    @Test
    void refusesToListADirectoryOutsideTheRootThroughTraversalOrAnAbsolutePath() {
        assertEquals("The path escapes the project root",
                failureReasonOf(capability().list(Path.of(".."))));
        assertEquals("The path escapes the project root",
                failureReasonOf(capability().list(temp.getParent())));
    }

    @Test
    void refusesTraversalThroughAMissingIntermediateDirectory() {
        assertEquals("The path escapes the project root",
                failureReasonOf(capability().readText(Path.of("missing", "..", "..", "outside.txt"))));
    }

    @Test
    void acceptsDotSegmentsThatStayInsideTheRoot() throws IOException {
        Files.createDirectories(temp.resolve("src"));
        Files.writeString(temp.resolve("src").resolve("a.txt"), "inside", StandardCharsets.UTF_8);

        assertEquals("inside", value(capability().readText(Path.of("src", "..", "src", ".", "a.txt"))));
    }

    @Test
    void refusesAPathUnderASymlinkedDirectoryThatPointsOutside() throws IOException {
        Path outside = Files.createTempDirectory("vintelli-outside-dir-");
        try {
            Files.writeString(outside.resolve("secret.txt"), "secret", StandardCharsets.UTF_8);
            try {
                Files.createSymbolicLink(temp.resolve("link"), outside);
            } catch (UnsupportedOperationException | FileSystemException e) {
                assumeTrue(false, "Symbolic links are not available on this runner");
            }

            assertEquals("The path escapes the project root",
                    failureReasonOf(capability().readText(Path.of("link", "secret.txt"))));
            assertEquals("The path escapes the project root",
                    failureReasonOf(capability().list(Path.of("link"))));
        } finally {
            deleteTree(outside);
        }
    }

    /**
     * The provider contract stays stable in the identifier, the provenance
     * and the always available health.
     */
    @Test
    void keepsTheProviderContractStable() {
        LocalReadFileCapability capability = capability();
        assertEquals("local", capability.identifier());
        assertEquals("local filesystem, confined to the project root", capability.provenance());
        assertTrue(capability.health().isAvailable());
    }

    private static String failureReasonOf(CapabilityResult<?> result) {
        assertTrue(result.isFailure(), "expected a failed result, got " + result);
        return assertInstanceOf(CapabilityResult.Failed.class, result).failure().reason();
    }

    private static <T> T value(CapabilityResult<T> result) {
        assertFalse(result.isFailure(), result.failureOf().map(CapabilityFailure::reason).orElse(""));
        return ((CapabilityResult.Ok<T>) result).value();
    }

    private static void deleteTree(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
