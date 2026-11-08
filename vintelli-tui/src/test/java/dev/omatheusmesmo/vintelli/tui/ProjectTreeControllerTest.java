package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.ProjectFileOperations;
import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectTreeControllerTest {

    @TempDir
    Path root;

    private final List<String> statuses = new ArrayList<>();

    private ProjectTreeController controllerWithProject() throws IOException {
        return controllerWithFiles("src/main/A.java", "README.md");
    }

    private ProjectTreeController controllerWithFiles(String... relatives) throws IOException {
        for (String relative : relatives) {
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "content");
        }
        ProjectTreeController[] created = new ProjectTreeController[1];
        created[0] = new ProjectTreeController(root, new ShellState(), search(root), fileOperations(root),
                statuses::add, () -> created[0].refresh(true), () -> { });
        ProjectTreeController controller = created[0];
        controller.start();
        controller.refresh(true);
        return controller;
    }

    private static ProjectSearch search(Path root) {
        return new ProjectSearch() {
            @Override
            public ProjectSearch.SearchTask files(String query, Consumer<List<ProjectSearch.FileMatch>> results) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ProjectSearch.SearchTask text(String query, boolean regex, Consumer<List<ProjectSearch.TextMatch>> results) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Path> listFiles() {
                return relativeEntries(root, Files::isRegularFile);
            }

            @Override
            public List<Path> listDirectories() {
                return relativeEntries(root, path -> Files.isDirectory(path) && !path.equals(root));
            }
        };
    }

    private static List<Path> relativeEntries(Path root, Predicate<Path> keep) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(keep).map(root::relativize).toList();
        } catch (IOException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static ProjectFileOperations fileOperations(Path root) {
        return new ProjectFileOperations() {
            @Override
            public CapabilityResult<Path> createFile(Path directory, String name) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CapabilityResult<Path> createDirectory(Path directory, String name) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CapabilityResult<Path> rename(Path entry, String name) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CapabilityResult<Path> delete(Path entry) {
                try {
                    Files.delete(root.resolve(entry));
                    return CapabilityResult.ok(entry);
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };
    }

    @Test
    void expandShowsChildrenAndCollapseHidesThem() throws IOException {
        ProjectTreeController controller = controllerWithProject();
        controller.selectPath("src");
        controller.expandSelected();
        assertTrue(controller.files().contains("src/main/A.java"), "expanding src should list the file under its chain");
        assertEquals("Expanded src/main", statuses.getLast());
        controller.collapseSelected();
        assertFalse(controller.files().contains("src/main/A.java"), "collapsing src should hide the file under its chain");
        assertEquals("Collapsed src/main", statuses.getLast());
    }

    @Test
    void aFolderWithOnlyFolderChildrenSharesOneRowNamedAfterItsLastFolder() throws IOException {
        String tui = "src/main/java/dev/omatheusmesmo/vintelli/tui";
        ProjectTreeController controller = controllerWithFiles(tui + "/ProjectPanel.java", "README.md");
        assertEquals(List.of(tui, "README.md"), controller.files());
        assertEquals(tui, controller.visibleNodes().getFirst().compactName());
        assertEquals(0, controller.visibleNodes().getFirst().depth());
        controller.selectPath("src");
        assertEquals(Optional.of(tui), controller.selectedRelative());
    }

    @Test
    void expandingACompactedRowOpensItsLastFolderAndCollapsingClosesIt() throws IOException {
        String tui = "src/main/java/dev/omatheusmesmo/vintelli/tui";
        ProjectTreeController controller = controllerWithFiles(tui + "/ProjectPanel.java", "README.md");
        controller.selectPath("src");
        controller.expandSelected();
        assertEquals(List.of(tui, tui + "/ProjectPanel.java", "README.md"), controller.files());
        assertEquals("Expanded " + tui, statuses.getLast());
        controller.collapseSelected();
        assertEquals(List.of(tui, "README.md"), controller.files());
        assertEquals("Collapsed " + tui, statuses.getLast());
        assertTrue(controller.activateSelected().isEmpty());
        assertEquals(List.of(tui, tui + "/ProjectPanel.java", "README.md"), controller.files(),
                "Enter on the compacted row toggles it like its chevron");
    }

    @Test
    void aChainStopsAtAFolderWithSeveralChildrenAndAtAFile() throws IOException {
        ProjectTreeController controller = controllerWithFiles(
                "src/main/java/dev/api/Api.java", "src/main/java/dev/tui/Tui.java", "lib/Main.java", "lib/Tool.java");
        assertEquals(List.of("lib", "src/main/java/dev"), controller.files());
        controller.selectPath("src");
        controller.expandSelected();
        assertEquals(List.of("lib", "src/main/java/dev", "src/main/java/dev/api", "src/main/java/dev/tui"),
                controller.files(), "a folder with two folder children starts its own rows");
        controller.selectPath("src/main/java/dev/api");
        controller.expandSelected();
        assertTrue(controller.files().contains("src/main/java/dev/api/Api.java"));
        assertEquals("src/main/java/dev/api", controller.selectedRelative().orElseThrow());
    }

    @Test
    void aChainEndsAtAFolderWhoseOnlyChildIsAFile() throws IOException {
        ProjectTreeController controller = controllerWithFiles("src/main/Main.java");
        assertEquals(List.of("src/main"), controller.files());
        controller.selectPath("src/main");
        controller.expandSelected();
        assertEquals(List.of("src/main", "src/main/Main.java"), controller.files());
    }

    @Test
    void anExpandedChainStaysOpenWhenANewSiblingBreaksIt() throws IOException {
        ProjectTreeController controller = controllerWithFiles("src/main/java/Main.java");
        controller.selectPath("src");
        controller.expandSelected();
        assertEquals(List.of("src/main/java", "src/main/java/Main.java"), controller.files());
        Path sibling = root.resolve("src/main/resources/data.txt");
        Files.createDirectories(sibling.getParent());
        Files.writeString(sibling, "content");
        controller.refresh(true);
        assertEquals(List.of("src/main", "src/main/java", "src/main/java/Main.java", "src/main/resources"),
                controller.files(), "the folders that were open stay open once the chain is broken");
    }

    @Test
    void theHorizontalOffsetShowsTheEndOfALongRowAndReturnsWhenItFits() throws IOException {
        ProjectTreeController controller = controllerWithProject();
        controller.trackColumn(50, 20);
        assertEquals(30, controller.leftColumn());
        controller.trackColumn(20, 20);
        assertEquals(0, controller.leftColumn());
        controller.trackColumn(10, 20);
        assertEquals(0, controller.leftColumn());
    }

    @Test
    void revealExpandsTheDirectoriesOfAFileAndSelectsIt() throws IOException {
        ProjectTreeController controller = controllerWithProject();
        String relative = controller.expandToFile(root.resolve("src/main/A.java"));
        controller.refresh(true);
        controller.selectPath(relative);
        assertEquals(Optional.of("src/main/A.java"), controller.selectedRelative());
    }

    @Test
    void vcsStateIsReadPerRowAndPublishingMovesTheRevision() throws IOException {
        ProjectTreeController controller = controllerWithProject();
        long before = controller.revision();
        controller.publishVcsStates(Map.of("README.md", VersionControlCapability.State.MODIFIED));
        assertEquals(VersionControlCapability.State.MODIFIED, controller.vcsState("README.md"));
        assertNull(controller.vcsState("src"));
        assertTrue(controller.revision() > before, "a VCS publish should mark the rows as changed");
    }

    @Test
    void requestedDeleteWaitsForConfirmationAndCancelKeepsTheFile() throws IOException {
        ProjectTreeController controller = controllerWithProject();
        controller.selectPath("README.md");
        controller.requestDelete();
        assertTrue(controller.deletePending(), "delete should wait for confirmation");
        controller.cancelDelete();
        assertFalse(controller.deletePending());
        assertTrue(Files.exists(root.resolve("README.md")), "cancelled delete should keep the file");
    }

    @Test
    void confirmedDeleteRemovesTheFileAndClearsThePendingTarget() throws IOException {
        ProjectTreeController controller = controllerWithProject();
        controller.selectPath("README.md");
        controller.requestDelete();
        assertTrue(controller.confirmDelete());
        assertFalse(Files.exists(root.resolve("README.md")));
        assertFalse(controller.deletePending());
        assertFalse(controller.files().contains("README.md"));
    }
}
