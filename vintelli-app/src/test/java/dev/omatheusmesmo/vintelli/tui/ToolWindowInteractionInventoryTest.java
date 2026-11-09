package dev.omatheusmesmo.vintelli.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Keeps every CDI-discovered tool window visible in the interaction coverage
 * inventory. New windows must declare behavior scenarios, a concrete tracked
 * gap, or an explicit passive role.
 */
@QuarkusTest
class ToolWindowInteractionInventoryTest {

    private static final Map<String, InventoryEntry> INVENTORY = inventory();
    private static final Map<String, String> NON_WINDOW_GAPS = Map.of(
            "editor.text-area", "Caret placement, selection, and scrolling are tracked by #195",
            "overlay.command-palette", "Pointer navigation and modal routing are tracked by #196",
            "overlay.project-search", "Pointer navigation and modal routing are tracked by #196");

    private final ToolWindowRegistry registry;

    @Inject
    ToolWindowInteractionInventoryTest(ToolWindowRegistry registry) {
        this.registry = registry;
    }

    @Test
    void everyRegisteredToolWindowHasAnExplicitInteractionClassification() {
        List<String> registeredIds = registry.toolWindows().stream().map(ToolWindow::id).toList();
        assertEquals(INVENTORY.keySet(), Set.copyOf(registeredIds),
                "Update the interaction inventory when adding or removing a tool window");

        for (Map.Entry<String, InventoryEntry> entry : INVENTORY.entrySet()) {
            assertNotNull(entry.getValue().kind(),
                    () -> "Tool window " + entry.getKey() + " needs an interaction classification");
            assertFalse(entry.getValue().reason().isBlank(),
                    () -> "Tool window " + entry.getKey() + " needs a contract scenario or a tracked/passive reason");
            switch (entry.getValue().kind()) {
                case CONTRACTED -> {
                    assertFalse(entry.getValue().coverageClass().isBlank(),
                            () -> "Compliant tool window " + entry.getKey() + " must name its contract test class");
                    assertFalse(entry.getValue().coverageMethod().isBlank(),
                            () -> "Compliant tool window " + entry.getKey() + " must name its contract test method");
                    Method coverageMethod = method(entry.getValue());
                    assertNotNull(coverageMethod,
                            () -> "Missing contract test class " + entry.getValue().coverageClass());
                    assertTrue(coverageMethod.isAnnotationPresent(Test.class),
                            () -> "Contract method must be a JUnit test: " + coverageMethod);
                }
                case TRACKED_GAP -> assertTrue(entry.getValue().reason().matches(".*#[0-9]+.*"),
                        () -> "Tracked gap for " + entry.getKey() + " must name its follow-up issue");
                case PASSIVE -> assertFalse(entry.getValue().reason().isBlank(),
                        () -> "Passive tool window " + entry.getKey() + " needs a concrete reason");
            }
        }
    }

    @Test
    void nonWindowInteractiveGapsRemainVisibleOutsideTheToolWindowInventory() {
        assertEquals(Set.of("editor.text-area", "overlay.command-palette", "overlay.project-search"),
                NON_WINDOW_GAPS.keySet());
        for (Map.Entry<String, String> gap : NON_WINDOW_GAPS.entrySet()) {
            assertTrue(gap.getValue().matches(".*#[0-9]+.*"),
                    () -> "Tracked component gap " + gap.getKey() + " must name its follow-up issue");
        }
    }

    private static Map<String, InventoryEntry> inventory() {
        Map<String, InventoryEntry> entries = new LinkedHashMap<>();
        entries.put("run", contracted("dev.omatheusmesmo.vintelli.tui.RunToolWindowInteractionContractTest",
                "keyboardEnterAndPointerRunButtonDispatchTheSameSelectedConfiguration"));
        entries.put("sample.extra", passive("Test-only registry fixture with no interactive controls"));
        entries.put("terminal", gap("Terminal tool window behavior is tracked by #4"));
        entries.put("problems", contracted("dev.omatheusmesmo.vintelli.tui.ProblemsToolWindowInteractionContractTest",
                "keyboardAndPointerNavigateTheSameProblemAndKeepWindowFocus"));
        entries.put("acp.agents", passive("Placeholder for future ACP sessions (#7)"));
        entries.put("structure", gap("Pointer selection and scrolling are tracked by #197"));
        entries.put("maven", contracted("dev.omatheusmesmo.vintelli.tui.MavenToolWindowInteractionContractTest",
                "keyboardAndPointerRunTheSameMavenPhase"));
        entries.put("editor", gap("Editor pointer behavior is tracked by #195"));
        return Map.copyOf(entries);
    }

    private static InventoryEntry gap(String reason) {
        return new InventoryEntry(Kind.TRACKED_GAP, reason, "", "");
    }

    private static InventoryEntry passive(String reason) {
        return new InventoryEntry(Kind.PASSIVE, reason, "", "");
    }

    private static InventoryEntry contracted(String testClass, String testMethod) {
        return new InventoryEntry(Kind.CONTRACTED, "Behavior contract is enforced by JUnit.", testClass, testMethod);
    }

    private static Method method(InventoryEntry entry) {
        try {
            return Class.forName(entry.coverageClass()).getDeclaredMethod(entry.coverageMethod());
        } catch (ClassNotFoundException | NoSuchMethodException failure) {
            throw new AssertionError("Contract test method not found: " + entry.coverageClass() + "#"
                    + entry.coverageMethod(), failure);
        }
    }

    private enum Kind {
        CONTRACTED,
        TRACKED_GAP,
        PASSIVE
    }

    private record InventoryEntry(Kind kind, String reason, String coverageClass, String coverageMethod) {
    }
}
