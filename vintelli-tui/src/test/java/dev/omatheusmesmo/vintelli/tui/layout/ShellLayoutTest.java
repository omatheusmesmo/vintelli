package dev.omatheusmesmo.vintelli.tui.layout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellLayoutTest {
    @Test
    void keyboardAndMouseSizesPreserveAdjoiningPanelsAndScaleWithTerminal() {
        ShellLayout layout = new ShellLayout();
        layout.resizeProject(1000, 100);
        assertEquals(84, layout.projectWidth(100));
        layout.resizeProject(-1000, 100);
        assertEquals(12, layout.projectWidth(100));
        layout.projectWidth(40, 100);
        assertEquals(80, layout.projectWidth(200));
        layout.resizeBottom(1000, 40);
        assertEquals(36, layout.bottomHeight(40));
        layout.resizeBottom(-1000, 40);
        assertEquals(3, layout.bottomHeight(40));
        layout.bottomHeight(10, 40);
        assertEquals(20, layout.bottomHeight(80));
    }

    @Test
    void tinyAndZeroTerminalsNeverProduceNegativeOrOverflowedDimensions() {
        ShellLayout layout = new ShellLayout(Double.NaN, Double.POSITIVE_INFINITY);
        assertEquals(ShellLayout.DEFAULT_PROJECT_RATIO, layout.projectRatio());
        assertEquals(ShellLayout.DEFAULT_BOTTOM_RATIO, layout.bottomRatio());
        for (int size = 0; size < 30; size++) {
            assertTrue(layout.projectWidth(size) >= 0 && layout.projectWidth(size) <= size);
            assertTrue(layout.bottomHeight(size) >= 0 && layout.bottomHeight(size) <= size);
        }
        layout.projectWidth(10, 0);
        layout.bottomHeight(10, 0);
        assertEquals(0, layout.projectWidth(0));
        assertEquals(0, layout.bottomHeight(0));
    }
}
