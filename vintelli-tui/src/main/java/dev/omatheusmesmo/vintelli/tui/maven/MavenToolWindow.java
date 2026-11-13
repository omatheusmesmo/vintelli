package dev.omatheusmesmo.vintelli.tui.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.omatheusmesmo.vintelli.tui.Marquee;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.omatheusmesmo.vintelli.tui.ToolWindow;
import dev.omatheusmesmo.vintelli.tui.keymap.KeyActions;
import dev.omatheusmesmo.vintelli.tui.maven.MavenProjectController.Row;
import dev.omatheusmesmo.vintelli.tui.maven.MavenProjectController.RowKind;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.widgets.paragraph.Paragraph;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

/**
 * Right anchored tree of the Maven modules and their lifecycle phases. Keys
 * and pointer events both resolve to the same named actions, so activating a
 * phase dispatches {@link KeyActions#MAVEN_ACTIVATE} whichever input was used.
 * The window never runs a process: it hands the chosen {@link MavenGoal} to
 * the run handler, which the shell connects to the Run window.
 */
@Singleton
public final class MavenToolWindow implements ToolWindow {

    private static final String MAVEN_GLYPH = "";
    private static final int WHEEL_STEP = 3;

    private final Provider<ShellThemes> themes;
    private final Path projectRoot;
    private final LongSupplier nanos;
    private final MavenProjectController controller;
    private final AtomicLong loadSequence = new AtomicLong();
    private volatile Consumer<MavenGoal> runHandler;
    private volatile Rect lastArea = Rect.ZERO;

    @Inject
    public MavenToolWindow(Provider<ShellThemes> themes,
            @ConfigProperty(name = "vintelli.local.project-root", defaultValue = ".") String projectRoot) {
        this(themes, Path.of(projectRoot));
    }

    public MavenToolWindow(Provider<ShellThemes> themes, Path projectRoot) {
        this(themes, projectRoot, System::nanoTime);
    }

    /** Takes the clock the double click is timed with, so a test does not depend on how fast the frames render. */
    public MavenToolWindow(Provider<ShellThemes> themes, Path projectRoot, LongSupplier nanos) {
        this.themes = themes;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.nanos = nanos;
        this.controller = new MavenProjectController(nanos);
    }

    public void useMarquee(boolean enabled) {
        controller.useMarquee(enabled);
    }

    public void onMarqueeChange(Runnable listener) {
        controller.onMarqueeChange(listener);
    }

    public void tickMarquee() {
        controller.tickMarquee();
    }

    public boolean marqueeMoving() {
        return controller.marqueeMoving();
    }

    /** The full text of the selected row or the status line while the marquee scrolls it, or empty. */
    public Optional<String> clippedText() {
        return controller.marqueeMoving() ? controller.selectionText() : Optional.empty();
    }

    @PostConstruct
    void initialize() {
        load();
    }

    /** Reads the project tree on a virtual thread; the window shows its loading state until the read ends. */
    public void load() {
        long sequence = loadSequence.incrementAndGet();
        controller.startLoading();
        Thread.ofVirtual().name("vintelli-maven-load").start(() -> {
            MavenProjectLoad result = MavenProjectReader.read(projectRoot);
            if (sequence == loadSequence.get()) {
                controller.finishLoading(result);
            }
        });
    }

    public void setRunHandler(Consumer<MavenGoal> handler) {
        this.runHandler = handler;
    }

    @Override
    public String id() {
        return "maven";
    }

    @Override
    public String title() {
        return "Maven";
    }

    @Override
    public char shortcut() {
        return '9';
    }

    @Override
    public int ordinal() {
        return 5;
    }

    @Override
    public String icon() {
        return MAVEN_GLYPH;
    }

    @Override
    public Anchor anchor() {
        return Anchor.RIGHT;
    }

    @Override
    public boolean capturesInput() {
        return true;
    }

    @Override
    public boolean claimsQuitChord() {
        return false;
    }

    @Override
    public String actionForKey(KeyEvent event) {
        if (event.isKey(KeyCode.ENTER)) {
            return KeyActions.MAVEN_ACTIVATE;
        }
        if (event.isKey(KeyCode.DOWN)) {
            return KeyActions.MAVEN_SELECT_NEXT;
        }
        if (event.isKey(KeyCode.UP)) {
            return KeyActions.MAVEN_SELECT_PREVIOUS;
        }
        if (event.isKey(KeyCode.RIGHT)) {
            return KeyActions.MAVEN_EXPAND;
        }
        if (event.isKey(KeyCode.LEFT)) {
            return KeyActions.MAVEN_COLLAPSE;
        }
        return null;
    }

    /** Space toggles the focused profile row; on any other row it is not handled. */
    @Override
    public boolean handleKey(KeyEvent event) {
        boolean space = event.code() == KeyCode.CHAR && event.codePoint() == ' ' && !event.hasCtrl() && !event.hasAlt();
        return space && controller.toggleSelectedProfile();
    }

    /**
     * A double click on a module or phase row activates it, the same action Enter dispatches; a single click only
     * selects. A profile row is a checkbox: one click toggles it, so it never takes part in a double click.
     */
    @Override
    public String actionForMouse(MouseEvent event) {
        if (!isLeftClick(event) || !inside(event)) {
            return null;
        }
        int index = rowIndex(event);
        if (index < 0 || controller.isProfileRow(index)) {
            return null;
        }
        return controller.registerClick(index, nanos.getAsLong()) ? KeyActions.MAVEN_ACTIVATE : null;
    }

    @Override
    public boolean handleMouse(MouseEvent event) {
        if (!inside(event)) {
            return false;
        }
        if (event.kind() == MouseEventKind.SCROLL_UP) {
            controller.scrollBy(-WHEEL_STEP);
            return true;
        }
        if (event.kind() == MouseEventKind.SCROLL_DOWN) {
            controller.scrollBy(WHEEL_STEP);
            return true;
        }
        if (!isLeftClick(event)) {
            return false;
        }
        int index = rowIndex(event);
        if (index < 0) {
            return false;
        }
        controller.select(index);
        controller.toggleSelectedProfile();
        return true;
    }

    @Override
    public boolean performAction(String actionId) {
        return performAction(actionId, null);
    }

    @Override
    public boolean performAction(String actionId, KeyEvent event) {
        switch (actionId) {
            case KeyActions.MAVEN_ACTIVATE -> controller.activateSelected().ifPresent(this::runGoal);
            case KeyActions.MAVEN_SELECT_NEXT -> controller.selectNext();
            case KeyActions.MAVEN_SELECT_PREVIOUS -> controller.selectPrevious();
            case KeyActions.MAVEN_EXPAND -> controller.expandSelected();
            case KeyActions.MAVEN_COLLAPSE -> controller.collapseSelected();
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public void render(Frame frame, Rect area) {
        lastArea = area;
        controller.fitViewport(area.height());
        Style style = themes.get().resolver("tool-window").toStyle();
        List<String> lines = new ArrayList<>();
        Optional<String> status = controller.statusMessage();
        if (status.isPresent()) {
            controller.fitSelected(area.width(), 0, CharWidth.of(status.get()));
            lines.add(scrolled(status.get(), area.width()));
        } else {
            List<Row> rows = controller.rows();
            int selected = controller.selected();
            int first = controller.scrollOffset();
            fitSelectedRow(rows, selected, area.width());
            for (int index = first; index < Math.min(rows.size(), first + area.height()); index++) {
                Row row = rows.get(index);
                if (index != selected) {
                    lines.add("  " + prefix(row) + label(row));
                    continue;
                }
                String marker = "› " + prefix(row);
                lines.add(marker + scrolled(label(row), area.width() - CharWidth.of(marker)));
            }
        }
        frame.renderWidget(Paragraph.builder()
                .text(Text.from(String.join("\n", lines)))
                .style(style)
                .build(), area);
    }

    private void runGoal(MavenGoal goal) {
        Consumer<MavenGoal> handler = runHandler;
        if (handler != null) {
            handler.accept(goal);
        }
    }

    private void fitSelectedRow(List<Row> rows, int selected, int width) {
        if (selected < 0 || selected >= rows.size()) {
            controller.fitSelected(width, 0, 0);
            return;
        }
        Row row = rows.get(selected);
        controller.fitSelected(width, CharWidth.of("› " + prefix(row)), CharWidth.of(label(row)));
    }

    private String scrolled(String text, int columns) {
        if (!controller.marqueeMoving() || columns <= 0) {
            return text;
        }
        return Marquee.window(text, controller.marqueeOffset(), columns);
    }

    private static String prefix(Row row) {
        String indent = "  ".repeat(row.depth());
        return switch (row.kind()) {
            case PHASE, PROFILES -> indent;
            case PROFILE -> indent + (row.checked() ? "[x] " : "[ ] ");
            case MODULE -> indent + (row.expanded() ? "▾ " : "▸ ");
        };
    }

    private static String label(Row row) {
        return row.kind() == RowKind.PHASE ? row.phase() : row.label();
    }

    private boolean inside(MouseEvent event) {
        Rect area = lastArea;
        return event.x() >= area.x() && event.x() < area.x() + area.width()
                && event.y() >= area.y() && event.y() < area.y() + area.height();
    }

    private int rowIndex(MouseEvent event) {
        return controller.rowAt(event.y() - lastArea.y());
    }

    private static boolean isLeftClick(MouseEvent event) {
        return event.isClick() && event.isLeftButton();
    }
}
