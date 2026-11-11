package dev.omatheusmesmo.vintelli.tui.menu;

import dev.omatheusmesmo.vintelli.tui.OverlayInput;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.Clear;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** Immediate-mode menu popup. Its returned action ids use the shell's normal dispatcher. */
public final class MenuPopup {

    private final MenuController controller;
    private final int originX;

    public MenuPopup(List<String> menus, Supplier<List<MenuEntry>> entries,
            Function<String, String> shortcuts) {
        this(menus, entries, shortcuts, 0);
    }

    /**
     * @param originX the column of the first menu label on the menu row, so
     *                the popup panels line up under the labels the toolbar draws
     */
    public MenuPopup(List<String> menus, Supplier<List<MenuEntry>> entries,
            Function<String, String> shortcuts, int originX) {
        this.controller = new MenuController(menus, entries, shortcuts);
        this.originX = originX;
    }

    /**
     * Column where the label of {@code index} starts. Every label occupies its
     * text plus one padding cell on each side, and the popup and the toolbar
     * both place their labels with this function.
     */
    public static int labelOffset(List<String> menus, int index, int originX) {
        return MenuController.labelOffset(menus, index, originX);
    }

    /** Width of one menu label including its padding cell on each side. */
    public static int labelWidth(String menu) {
        return MenuController.labelWidth(menu);
    }

    public void open(int index) {
        controller.open(index);
    }

    public void open(int index, String selectedAction) {
        controller.open(index, selectedAction);
    }

    public void close() {
        controller.close();
    }

    public boolean isOpen() {
        return controller.isOpen();
    }

    public int menuIndex() {
        return controller.menuIndex();
    }

    public List<MenuEntry> visibleEntries() {
        return controller.visibleEntries();
    }

    public String selectedReason() {
        return controller.selectedReason();
    }

    public Optional<String> handleKey(KeyEvent key) {
        return controller.press(OverlayInput.key(key));
    }

    /** Coordinates are terminal cells from the last render, including the area's origin. */
    public Optional<String> click(int x, int y) {
        return controller.click(x, y);
    }

    /** Whether the cell lies inside the panel drawn by the last render. */
    public boolean contains(int x, int y) {
        return controller.contains(x, y);
    }

    public void render(Frame frame, Rect area) {
        render(frame, area, originX);
    }

    /**
     * Draws the panel below the first row of {@code area}, starting at column
     * {@code panelOrigin} of that area, or at its right edge when the panel
     * would not fit there.
     */
    public void render(Frame frame, Rect area, int panelOrigin) {
        Optional<MenuController.Placement> placed = controller.place(OverlayInput.region(area), panelOrigin);
        if (placed.isEmpty()) {
            return;
        }
        MenuController.Placement placement = placed.get();
        Rect panel = OverlayInput.rect(placement.panel());
        frame.renderWidget(Clear.INSTANCE, panel);
        frame.renderWidget(Block.builder().borders(Borders.ALL).build(), panel);
        List<Line> lines = new ArrayList<>();
        for (MenuController.Row row : placement.rows()) {
            Style style = Style.create();
            if (row.highlighted()) {
                style = style.reversed();
            }
            if (row.dimmed()) {
                style = style.dim();
            }
            lines.add(Line.from(Span.styled(row.text(), style)));
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(lines)).build(), OverlayInput.rect(placement.inner()));
    }
}
