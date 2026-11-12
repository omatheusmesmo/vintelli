package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.search.ProjectSearch;
import dev.omatheusmesmo.vintelli.tui.icons.FileIcons;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.omatheusmesmo.vintelli.tui.icons.JavaTypeKind;
import dev.omatheusmesmo.vintelli.tui.icons.JavaTypeKinds;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.keymap.Keymap;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectPanelTest {

    private static final Rect AREA = new Rect(0, 0, 40, 8);
    private static final String ELLIPSIS = "…";
    private static final String DEEP_DIRECTORY = "docs/guide/api";
    private static final String DEEP_FILE = DEEP_DIRECTORY + "/deep-clipping-test-file-name-here.txt";
    private static final String DEEP_SIBLING = DEEP_DIRECTORY + "/other.txt";
    private static final String DEEP_NAME = "deep-clipping-test-file-name-here.txt";

    @TempDir
    Path root;

    @Test
    void theRowDrawsTheExtensionIconUntilTheScanAnswersThenTheKindIcon() throws IOException {
        Files.writeString(root.resolve("Shape.java"), "public interface Shape {}");
        Files.writeString(root.resolve("Point.java"), "public record Point(int x) {}");
        List<Runnable> scans = new ArrayList<>();
        ProjectPanel panel = panel(controller(scans));
        FileIcons icons = FileIcons.resolve();
        String extensionShape = row(icons, "Shape.java", null);
        String extensionPoint = row(icons, "Point.java", null);

        String firstFrame = render(panel);
        assertTrue(firstFrame.contains(extensionShape), firstFrame);
        assertTrue(firstFrame.contains(extensionPoint), firstFrame);
        assertFalse(scans.isEmpty(), "unknown Java files are scanned off the frame");

        scans.forEach(Runnable::run);
        String scannedFrame = render(panel);
        assertTrue(scannedFrame.contains(row(icons, "Shape.java", JavaTypeKind.INTERFACE)), scannedFrame);
        assertTrue(scannedFrame.contains(row(icons, "Point.java", JavaTypeKind.RECORD)), scannedFrame);
        assertFalse(scannedFrame.contains(extensionShape), scannedFrame);
    }

    @Test
    void aJavaFileWithoutATypeKeepsTheExtensionIcon() throws IOException {
        Files.writeString(root.resolve("Notes.java"), "// nothing declared here\n");
        List<Runnable> scans = new ArrayList<>();
        ProjectPanel panel = panel(controller(scans));
        FileIcons icons = FileIcons.resolve();

        render(panel);
        scans.forEach(Runnable::run);

        assertTrue(render(panel).contains(row(icons, "Notes.java", null)));
    }

    /**
     * A scan that finishes while the rows are being built, which an executor
     * that runs the task at once reproduces for a file the refresh did not
     * revalidate, must not be taken for a scan the panel already drew: the
     * next frame has to show the kind.
     */
    @Test
    void aScanThatEndsWhileTheRowsAreBuiltIsDrawnOnTheNextFrame() throws IOException {
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/Deep.java"), "public enum Deep { ONE }");
        ProjectTreeController controller = new ProjectTreeController(root, new ShellState(), search(root), null,
                message -> { }, () -> { }, () -> { }, new JavaTypeKinds(Files::readString, Runnable::run));
        controller.start();
        controller.refresh(false);
        ProjectPanel panel = panel(controller);
        FileIcons icons = FileIcons.resolve();
        render(panel);
        controller.selectPath("sub");
        controller.expandSelected();

        String firstFrame = render(panel);
        String secondFrame = render(panel);

        assertTrue(firstFrame.contains("Deep.java"), firstFrame);
        assertTrue(secondFrame.contains(row(icons, "Deep.java", JavaTypeKind.ENUM)), secondFrame);
    }

    @Test
    void aDeepRevealClipsTheSelectedRowAtItsEndAndKeepsTheNamesOfTheShallowRows() throws IOException {
        ProjectTreeController controller = revealedDeepFile(new ArrayList<>());
        ProjectPanel panel = panel(controller);
        FileIcons icons = FileIcons.resolve();

        String frame = render(panel);

        assertTrue(frame.contains(icons.folderIconFor("docs").symbol(icons.mode()) + " docs"), frame);
        assertTrue(frame.contains(ELLIPSIS), frame);
    }

    @Test
    void aDisclosureMarkerStillTogglesItsOwnRowAfterADeepReveal() throws IOException {
        ProjectTreeController controller = revealedDeepFile(new ArrayList<>());
        ProjectPanel panel = panel(controller);
        render(panel);

        panel.clicked(MouseEvent.press(MouseButton.LEFT, 4, 1));

        assertFalse(controller.files().contains("docs/guide/api"), "the guide marker collapses the guide row");
        assertTrue(controller.files().contains("docs/other.md"), "the docs row stays expanded");
    }

    @Test
    void theSelectedNameScrollsAtEachTickAndTheIndentAndTheIconStayPut() throws IOException {
        AtomicLong now = new AtomicLong();
        ProjectTreeController controller = marqueeController(now);
        ProjectPanel panel = panel(controller);
        FileIcons icons = FileIcons.resolve();

        assertScrollsAtTicks(controller, panel, now, icons);
    }

    @Test
    void theAsciiFaceScrollsTheSameWayAtEachTick() throws IOException {
        String previous = System.getProperty(IconMode.CONFIG_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, "ascii");
        try {
            AtomicLong now = new AtomicLong();
            ProjectTreeController controller = marqueeController(now);
            ProjectPanel panel = panel(controller);
            FileIcons icons = FileIcons.resolve();

            assertEquals(IconMode.ASCII, icons.mode());
            assertScrollsAtTicks(controller, panel, now, icons);
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    void aSelectedNameThatFitsDoesNotMove() throws IOException {
        AtomicLong now = new AtomicLong();
        ProjectTreeController controller = marqueeController(now);
        controller.selectPath("README.md");
        ProjectPanel panel = panel(controller);
        render(panel);

        now.set(60_000_000_000L);
        controller.tickMarquee();

        assertFalse(controller.marqueeMoving());
        assertEquals(0, controller.marqueeOffset());
        assertTrue(render(panel).contains("README.md"));
    }

    @Test
    void aDisclosureMarkerStillTogglesItsRowWhileTheSelectedNameScrolls() throws IOException {
        AtomicLong now = new AtomicLong();
        ProjectTreeController controller = marqueeController(now);
        ProjectPanel panel = panel(controller);
        render(panel);
        controller.tickMarquee();
        now.set(2_500_000_000L);
        controller.tickMarquee();
        render(panel);
        assertTrue(controller.marqueeOffset() > 0, "the selected name has scrolled");

        panel.clicked(MouseEvent.press(MouseButton.LEFT, 4, 1));

        assertFalse(controller.files().contains("docs/guide/api"), "the guide marker collapses the guide row");
        assertTrue(controller.files().contains("docs/other.md"), "the docs row stays expanded");
    }

    private void assertScrollsAtTicks(ProjectTreeController controller, ProjectPanel panel, AtomicLong now,
            FileIcons icons) {
        String prefix = icons.iconFor(DEEP_NAME, null).symbol(icons.mode()) + " ";
        controller.tickMarquee();

        String start = render(panel);
        assertTrue(start.contains(prefix + "deep-clip"), start);
        assertFalse(start.contains(ELLIPSIS), "the marquee replaces the end ellipsis: " + start);

        now.set(1_150_000_000L);
        controller.tickMarquee();
        String second = render(panel);
        assertTrue(second.contains(prefix + "eep-clipping"), second);
        assertFalse(second.contains(prefix + "deep-clip"), second);

        now.set(2_500_000_000L);
        controller.tickMarquee();
        String end = render(panel);
        assertTrue(end.contains("here.txt"), end);
        assertTrue(end.contains(prefix), "the icon stays in place at the end: " + end);
    }

    private ProjectTreeController marqueeController(AtomicLong now) throws IOException {
        ProjectTreeController controller = revealedDeepFile(new ArrayList<>());
        controller.useClock(now::get);
        controller.useMarquee(true);
        return controller;
    }

    private static void restoreProperty(String previous) {
        if (previous == null) {
            System.clearProperty(IconMode.CONFIG_PROPERTY);
        } else {
            System.setProperty(IconMode.CONFIG_PROPERTY, previous);
        }
    }

    private ProjectTreeController revealedDeepFile(List<Runnable> scans) throws IOException {
        Files.createDirectories(root.resolve(DEEP_DIRECTORY));
        Files.writeString(root.resolve(DEEP_FILE), "deep");
        Files.writeString(root.resolve(DEEP_SIBLING), "sibling");
        Files.writeString(root.resolve("docs/guide/notes.md"), "notes");
        Files.writeString(root.resolve("docs/other.md"), "other");
        Files.writeString(root.resolve("README.md"), "# readme");
        ProjectTreeController controller = controller(scans);
        controller.expandAll();
        controller.selectPath(DEEP_FILE);
        return controller;
    }

    private static String row(FileIcons icons, String name, JavaTypeKind kind) {
        return icons.iconFor(name, kind).symbol(icons.mode()) + " " + name;
    }

    @Test
    void aProjectCommandRunsAfterTheOverlaysCloseAndAnUnknownActionIsNotHandled() throws IOException {
        Files.writeString(root.resolve("First.java"), "class First {}");
        Files.writeString(root.resolve("Second.java"), "class Second {}");
        ProjectTreeController controller = controller(new ArrayList<>());
        ProjectPanel panel = panel(controller);
        Path before = controller.selectedPath().orElseThrow();
        AtomicInteger closes = new AtomicInteger();

        assertTrue(panel.handle(KeyActions.MOVE_DOWN, closes::incrementAndGet));
        assertEquals(1, closes.get());
        assertFalse(before.equals(controller.selectedPath().orElseThrow()));

        assertFalse(panel.handle(KeyActions.SAVE, closes::incrementAndGet));
        assertEquals(1, closes.get());
    }

    private ProjectTreeController controller(List<Runnable> scans) {
        ProjectTreeController controller = new ProjectTreeController(root, new ShellState(), search(root), null,
                message -> { }, () -> { }, () -> { }, new JavaTypeKinds(Files::readString, scans::add));
        controller.start();
        controller.refresh(false);
        return controller;
    }

    private ProjectPanel panel(ProjectTreeController controller) {
        return new ProjectPanel(controller, FileIcons.resolve(), false, new ReentrantLock(), host());
    }

    private static String render(ProjectPanel panel) {
        Buffer buffer = Buffer.empty(AREA);
        DefaultRenderContext context = DefaultRenderContext.createEmpty();
        context.setStyleEngine(new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null).engine());
        panel.render(Frame.forTesting(buffer), AREA, context);
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < buffer.height(); y++) {
            for (int x = 0; x < buffer.width(); x++) {
                text.append(buffer.get(x, y).symbol());
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static ProjectPanel.Host host() {
        return new ProjectPanel.Host() {
            @Override
            public Keymap keymap() {
                return null;
            }

            @Override
            public boolean shortcutsBlocked() {
                return false;
            }

            @Override
            public boolean layoutVisible() {
                return true;
            }

            @Override
            public void focusPanel() {
            }

            @Override
            public void requestFocus() {
            }

            @Override
            public void beginInput(ProjectTreeController.InputMode mode) {
            }

            @Override
            public void refreshTree() {
            }

            @Override
            public void openFile(Path file) {
            }

            @Override
            public boolean pendingFocus() {
                return false;
            }

            @Override
            public void clearPendingFocus() {
            }

            @Override
            public void releaseSplitterDrag() {
            }

            @Override
            public EventResult splitterMouse(MouseEvent mouse) {
                return EventResult.UNHANDLED;
            }

            @Override
            public void togglePanel() {
            }

            @Override
            public void revealActiveFile() {
            }
        };
    }

    private static ProjectSearch search(Path root) {
        return new ProjectSearch() {
            @Override
            public ProjectSearch.SearchTask files(String query, Consumer<List<ProjectSearch.FileMatch>> results) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ProjectSearch.SearchTask text(String query, boolean regex,
                    Consumer<List<ProjectSearch.TextMatch>> results) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Path> listFiles() {
                try (var files = Files.walk(root)) {
                    return files.filter(Files::isRegularFile).map(file -> root.relativize(file)).toList();
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };
    }
}
