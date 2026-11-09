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
        Files.createDirectories(root.resolve("src/main"));
        Files.writeString(root.resolve("src/main/A.java"), "class A {}");
        Files.writeString(root.resolve("README.md"), "# readme");
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
        assertTrue(controller.files().contains("src/main"), "expanding src should list its directory");
        assertEquals("Expanded src", statuses.getLast());
        controller.collapseSelected();
        assertFalse(controller.files().contains("src/main"), "collapsing src should hide its directory");
        assertEquals("Collapsed src", statuses.getLast());
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
    void expandAllOpensEveryDirectoryAndCollapseAllFoldsThemBackToTheTopFolder() throws IOException {
        ProjectTreeController controller = controllerWithProject();
        controller.expandAll();
        assertEquals(List.of("src", "src/main", "src/main/A.java", "README.md"), controller.files());
        assertEquals("Expanded all", statuses.getLast());

        controller.selectPath("src/main/A.java");
        controller.collapseAll();
        assertEquals(List.of("src", "README.md"), controller.files(), "collapse all folds every directory");
        assertEquals(Optional.of("src"), controller.selectedRelative(),
                "the selection moves to the top folder that holds the collapsed row");
        assertEquals("Collapsed all", statuses.getLast());
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
