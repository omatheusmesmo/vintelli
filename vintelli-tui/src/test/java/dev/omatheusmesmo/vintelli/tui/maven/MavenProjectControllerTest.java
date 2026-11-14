package dev.omatheusmesmo.vintelli.tui.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import dev.omatheusmesmo.vintelli.api.maven.MavenLifecycle;
import dev.omatheusmesmo.vintelli.tui.DoubleClickTracker;
import dev.omatheusmesmo.vintelli.tui.maven.MavenProjectController.Row;
import dev.omatheusmesmo.vintelli.tui.maven.MavenProjectController.RowKind;

class MavenProjectControllerTest {

    private static final MavenModule CHILD = new MavenModule("vintelli-tui", "vintelli-tui", List.of());
    private static final MavenModule NESTED = new MavenModule("runtime", "core/runtime", List.of());
    private static final MavenModule CORE = new MavenModule("core", "core", List.of(NESTED));
    private static final MavenModule ROOT = new MavenModule("vintelli", "", List.of(CHILD, CORE));

    @Test
    void startsLoadingBeforeAnyTreeIsRead() {
        MavenProjectController controller = new MavenProjectController();

        assertEquals(Optional.of("Loading Maven project..."), controller.statusMessage());
        assertEquals(List.of(), controller.rows());
    }

    @Test
    void reportsAMissingOrUnreadableProjectAsAReasonNotAnEmptyTree() {
        MavenProjectController controller = new MavenProjectController();

        controller.finishLoading(new MavenProjectLoad.Missing("no pom.xml in the project root"));
        assertEquals(Optional.of("No Maven project: no pom.xml in the project root"), controller.statusMessage());
        assertEquals(List.of(), controller.rows());

        controller.finishLoading(new MavenProjectLoad.Unreadable("cannot parse pom.xml"));
        assertEquals(Optional.of("Cannot read the Maven project: cannot parse pom.xml"), controller.statusMessage());
    }

    @Test
    void rootShowsItsPhasesInLifecycleOrderBeforeItsModules() {
        MavenProjectController controller = loaded();

        List<Row> rows = controller.rows();

        assertEquals("vintelli", rows.getFirst().label());
        assertEquals(RowKind.MODULE, rows.getFirst().kind());
        assertTrue(rows.getFirst().expanded());
        assertEquals(RowKind.LIFECYCLE, rows.get(1).kind());
        List<String> rootPhases = rows.subList(2, 2 + MavenLifecycle.PHASES.size()).stream()
                .map(Row::phase).toList();
        assertEquals(MavenLifecycle.PHASES, rootPhases);
        assertEquals("clean", rootPhases.getFirst());
        assertEquals("site", rootPhases.getLast());
        Row firstModule = rows.get(2 + MavenLifecycle.PHASES.size());
        assertEquals("vintelli-tui", firstModule.label());
        assertFalse(firstModule.expanded());
    }

    @Test
    void activatingAPhaseOfTheRootRunsItWithoutAModuleSelector() {
        MavenProjectController controller = loaded();
        selectRow(controller, phaseOf("", "validate"));

        MavenGoal goal = controller.activateSelected().orElseThrow();

        assertEquals("maven:validate", goal.identifier());
        assertEquals(List.of(), goal.arguments());
    }

    @Test
    void activatingAPhaseOfAModuleRunsItWithThatModuleSelected() {
        MavenProjectController controller = loaded();
        selectModule(controller, "vintelli-tui");
        controller.expandSelected();
        selectRow(controller, phaseOf("vintelli-tui", "validate"));

        MavenGoal goal = controller.activateSelected().orElseThrow();

        assertEquals(new MavenGoal("validate", "vintelli-tui"), goal);
        assertEquals(List.of("-pl", "vintelli-tui"), goal.arguments());
    }

    @Test
    void activatingANestedModuleUsesItsRelativePathAsTheSelector() {
        MavenProjectController controller = loaded();
        selectModule(controller, "core");
        controller.expandSelected();
        selectModule(controller, "runtime");
        controller.expandSelected();
        selectRow(controller, phaseOf("core/runtime", "validate"));

        MavenGoal goal = controller.activateSelected().orElseThrow();

        assertEquals(new MavenGoal("validate", "core/runtime"), goal);
    }

    @Test
    void activatingAModuleNodeTogglesItsExpansionAndRunsNothing() {
        MavenProjectController controller = loaded();
        selectModule(controller, "vintelli-tui");

        assertTrue(controller.activateSelected().isEmpty());
        assertTrue(controller.rows().get(controller.selected()).expanded());

        assertTrue(controller.activateSelected().isEmpty());
        assertFalse(controller.rows().get(controller.selected()).expanded());
    }

    @Test
    void rightExpandsTheSelectedModuleAndLeftCollapsesIt() {
        MavenProjectController controller = loaded();
        int rowsBefore = controller.rows().size();
        selectModule(controller, "vintelli-tui");

        controller.expandSelected();
        assertEquals(rowsBefore + MavenLifecycle.PHASES.size() + 1, controller.rows().size());

        controller.collapseSelected();
        assertEquals(rowsBefore, controller.rows().size());
        assertEquals("vintelli-tui", controller.rows().get(controller.selected()).label());
    }

    @Test
    void leftOnAPhaseMovesToItsModuleAndLeftOnAClosedModuleMovesToItsParent() {
        MavenProjectController controller = loaded();
        selectModule(controller, "core");
        controller.expandSelected();
        selectRow(controller, phaseOf("core", "validate"));

        controller.collapseSelected();
        assertEquals("core", selectedRow(controller).label());
        assertEquals(RowKind.MODULE, selectedRow(controller).kind());
        assertTrue(selectedRow(controller).expanded());

        controller.collapseSelected();
        assertEquals("core", selectedRow(controller).label());
        assertFalse(selectedRow(controller).expanded());

        controller.collapseSelected();
        assertEquals("vintelli", selectedRow(controller).label());
    }

    @Test
    void expandingAPhaseDoesNothing() {
        MavenProjectController controller = loaded();
        controller.selectNext();
        int rowsBefore = controller.rows().size();

        controller.expandSelected();

        assertEquals(rowsBefore, controller.rows().size());
    }

    @Test
    void selectionNeverLeavesTheTreeAtEitherEnd() {
        MavenProjectController controller = loaded();

        controller.selectPrevious();
        assertEquals(0, controller.selected());

        for (int step = 0; step < controller.rows().size() + 5; step++) {
            controller.selectNext();
        }
        assertEquals(controller.rows().size() - 1, controller.selected());
    }

    @Test
    void theViewportFollowsTheSelectionAndClampsTheScroll() {
        MavenProjectController controller = loaded();
        controller.fitViewport(4);

        for (int step = 0; step < 10; step++) {
            controller.selectNext();
        }

        assertEquals(10, controller.selected());
        assertEquals(7, controller.scrollOffset());
        controller.scrollBy(1000);
        assertEquals(controller.rows().size() - 4, controller.scrollOffset());
        controller.scrollBy(-1000);
        assertEquals(0, controller.scrollOffset());
    }

    @Test
    void aShrunkViewportKeepsTheSelectionInsideTheVisibleRows() {
        MavenProjectController controller = loaded();
        controller.fitViewport(20);
        controller.select(15);

        controller.fitViewport(3);

        assertTrue(controller.selected() >= controller.scrollOffset());
        assertTrue(controller.selected() < controller.scrollOffset() + 3);
    }

    @Test
    void aRowIsHitOnlyInsideTheViewportAndInsideTheRows() {
        MavenProjectController controller = loaded();
        controller.fitViewport(3);
        controller.scrollBy(2);

        assertEquals(2, controller.rowAt(0));
        assertEquals(4, controller.rowAt(2));
        assertEquals(-1, controller.rowAt(3));
        assertEquals(-1, controller.rowAt(-1));
    }

    @Test
    void aClickSelectsTheRowAndAClickOutsideTheRowsSelectsNothing() {
        MavenProjectController controller = loaded();
        controller.fitViewport(5);

        controller.select(3);
        controller.select(controller.rows().size());

        assertEquals(3, controller.selected());
    }

    @Test
    void aSecondPressOnTheSameRowWithinTheWindowCompletesADoubleClick() {
        MavenProjectController controller = loaded();
        long start = 1_000_000_000L;

        long window = DoubleClickTracker.WINDOW_NANOS;
        assertFalse(controller.registerClick(4, start));
        assertFalse(controller.registerClick(5, start + 10));
        assertFalse(controller.registerClick(5, start + 10 + window + 1));
        assertTrue(controller.registerClick(5, start + 10 + window + 2));
        assertFalse(controller.registerClick(99, start + 10 + 2 * window));
    }

    @Test
    void reloadingStartsFromTheRootAgain() {
        MavenProjectController controller = loaded();
        selectModule(controller, "vintelli-tui");
        controller.expandSelected();

        controller.startLoading();
        controller.finishLoading(new MavenProjectLoad.Loaded(ROOT));

        assertEquals(0, controller.selected());
        assertEquals(2 + MavenLifecycle.PHASES.size() + 2, controller.rows().size());
    }

    @Test
    void aGoalAcceptsADeclaredPluginGoalOrLifecyclePhase() {
        assertEquals("maven:dependency:tree", new MavenGoal("dependency:tree", "").identifier());
        assertEquals("maven:deploy", new MavenGoal("deploy", "").identifier());
    }

    @Test
    void listsAndActivatesPluginGoalsWithTheMavenPrefix() {
        MavenPlugin plugin = new MavenPlugin("org.apache.maven.plugins", "maven-compiler-plugin",
                List.of("compile"));
        MavenProjectController controller = new MavenProjectController();
        controller.finishLoading(new MavenProjectLoad.Loaded(
                new MavenModule("vintelli", "", List.of(), List.of(plugin), List.of())));

        Row goalRow = controller.rows().stream().filter(row -> row.kind() == RowKind.GOAL).findFirst().orElseThrow();
        assertEquals("compiler:compile", goalRow.phase());
        controller.select(controller.rows().indexOf(goalRow));
        assertEquals("maven:compiler:compile", controller.activateSelected().orElseThrow().identifier());
    }

    @Test
    void skipTestsAndProfilesAreAppliedToTheNextGoal() {
        MavenProjectController controller = loaded();
        selectRow(controller, phaseOf("", "test"));
        controller.toggleSkipTests();

        assertEquals(List.of("-Pci", "-DskipTests"), new MavenGoal("test", "", List.of("ci"), true).arguments());
    }

    @Test
    void aSelectedRowThatOverflowsScrollsOnTheTicksAndRestartsWhenTheSelectionMoves() {
        AtomicLong now = new AtomicLong();
        MavenProjectController controller = new MavenProjectController(now::get);
        controller.finishLoading(new MavenProjectLoad.Loaded(ROOT));
        controller.useMarquee(true);
        selectRow(controller, phaseOf("", "validate"));
        controller.fitSelected(12, 4, 20);
        assertTrue(controller.marqueeMoving());
        controller.tickMarquee();

        now.set(1_150_000_000L);
        controller.tickMarquee();
        assertEquals(1, controller.marqueeOffset());

        controller.select(0);
        controller.tickMarquee();
        assertEquals(0, controller.marqueeOffset());
    }

    @Test
    void aRowThatFitsOrAMarqueeThatIsOffDoesNotMove() {
        MavenProjectController controller = loaded();
        selectRow(controller, phaseOf("", "validate"));

        controller.fitSelected(40, 4, 20);
        assertFalse(controller.marqueeMoving(), "the default is off");

        controller.useMarquee(true);
        controller.fitSelected(40, 4, 20);
        assertFalse(controller.marqueeMoving(), "a row that fits does not move");
    }

    @Test
    void theStatusLineScrollsItsMessageWhenTheMessageIsWiderThanTheWindow() {
        AtomicLong now = new AtomicLong();
        MavenProjectController controller = new MavenProjectController(now::get);
        controller.useMarquee(true);
        controller.finishLoading(new MavenProjectLoad.Missing("no pom.xml under the project root"));
        controller.fitSelected(12, 0, controller.selectionText().orElseThrow().length());
        controller.tickMarquee();

        now.set(1_150_000_000L);
        controller.tickMarquee();

        assertTrue(controller.marqueeMoving());
        assertEquals(1, controller.marqueeOffset());
        assertEquals("No Maven project: no pom.xml under the project root", controller.selectionText().orElseThrow());
    }

    @Test
    void aProfileRowIsDrawnUnderTheProfilesGroupAfterThePhasesOfItsModule() {
        MavenProjectController controller = profiled(List.of("ci"), List.of("fast", "ci"));
        selectModule(controller, "core");
        controller.expandSelected();

        List<Row> rows = controller.rows();
        int group = indexOf(rows, row -> row.kind() == RowKind.PROFILES && "core".equals(row.selector()));
        assertEquals("site", rows.get(group - 1).phase());
        assertEquals(List.of("fast", "ci"), rows.subList(group + 1, group + 3).stream().map(Row::label).toList());
        assertFalse(rows.get(group + 1).checked());
    }

    @Test
    void togglingAProfileByEnterAndSpaceChangesItsCheck() {
        MavenProjectController controller = profiled(List.of("ci"), List.of("fast", "ci"));
        selectModule(controller, "core");
        controller.expandSelected();

        selectRow(controller, profileOf("core", "fast"));
        assertTrue(controller.activateSelected().isEmpty());
        assertTrue(selectedRow(controller).checked());

        assertTrue(controller.toggleSelectedProfile());
        assertFalse(selectedRow(controller).checked());

        int ci = indexOf(controller.rows(), profileOf("core", "ci"));
        assertTrue(controller.isProfileRow(ci));
        assertFalse(controller.isProfileRow(indexOf(controller.rows(), phaseOf("core", "validate"))));
        assertFalse(controller.isProfileRow(-1));
        assertFalse(controller.isProfileRow(controller.rows().size()));
    }

    @Test
    void spaceTogglesOnlyAProfileRow() {
        MavenProjectController controller = profiled(List.of("ci"), List.of("fast"));
        selectRow(controller, phaseOf("", "validate"));

        assertFalse(controller.toggleSelectedProfile());
        assertEquals(List.of(), controller.rows().stream().filter(Row::checked).toList());
    }

    @Test
    void theSameProfileIdInTwoModulesIsSelectedIndependently() {
        MavenProjectController controller = profiled(List.of("ci"), List.of("ci"));
        selectRow(controller, profileOf("", "ci"));
        controller.activateSelected();

        selectModule(controller, "core");
        controller.expandSelected();
        int core = indexOf(controller.rows(), profileOf("core", "ci"));
        assertFalse(controller.rows().get(core).checked());

        controller.select(core);
        controller.activateSelected();
        assertTrue(controller.rows().get(core).checked());
        assertTrue(controller.rows().get(indexOf(controller.rows(), profileOf("", "ci"))).checked());
    }

    @Test
    void runningAPhaseAddsTheSelectedProfilesOfItsModuleInPomOrder() {
        MavenProjectController controller = profiled(List.of("ci", "docs"), List.of("fast", "ci"));
        selectRow(controller, profileOf("", "docs"));
        controller.activateSelected();
        selectModule(controller, "core");
        controller.expandSelected();
        selectRow(controller, profileOf("core", "ci"));
        controller.activateSelected();
        selectRow(controller, profileOf("core", "fast"));
        controller.activateSelected();
        selectRow(controller, phaseOf("core", "validate"));

        MavenGoal goal = controller.activateSelected().orElseThrow();

        assertEquals(List.of("-pl", "core", "-Pfast,ci"), goal.arguments());
        selectRow(controller, phaseOf("", "validate"));
        assertEquals(List.of("-Pdocs"), controller.activateSelected().orElseThrow().arguments());
    }

    @Test
    void aPhaseRunsWithoutAProfileArgumentWhenNothingIsSelected() {
        MavenProjectController controller = profiled(List.of("ci"), List.of("fast"));
        selectModule(controller, "core");
        controller.expandSelected();
        selectRow(controller, phaseOf("core", "validate"));

        assertEquals(List.of("-pl", "core"), controller.activateSelected().orElseThrow().arguments());
    }

    @Test
    void theProfileSelectionSurvivesAReloadAndDropsIdsThatNoLongerExist() {
        MavenProjectController controller = profiled(List.of("ci"), List.of("fast", "ci"));
        selectModule(controller, "core");
        controller.expandSelected();
        selectRow(controller, profileOf("core", "fast"));
        controller.activateSelected();
        selectRow(controller, profileOf("core", "ci"));
        controller.activateSelected();

        controller.startLoading();
        controller.finishLoading(new MavenProjectLoad.Loaded(profiledTree(List.of("ci"), List.of("ci"))));
        selectModule(controller, "core");
        controller.expandSelected();
        selectRow(controller, phaseOf("core", "validate"));
        assertEquals(List.of("-pl", "core", "-Pci"), controller.activateSelected().orElseThrow().arguments());

        controller.startLoading();
        controller.finishLoading(new MavenProjectLoad.Loaded(profiledTree(List.of("ci"), List.of("docs"))));
        selectModule(controller, "core");
        controller.expandSelected();
        selectRow(controller, phaseOf("core", "validate"));
        assertEquals(List.of("-pl", "core"), controller.activateSelected().orElseThrow().arguments());
    }

    @Test
    void aGoalKeepsTheProfilesItWasGiven() {
        MavenGoal goal = new MavenGoal("test", "core", List.of("fast", "ci"));

        assertEquals(List.of("-pl", "core", "-Pfast,ci"), goal.arguments());
        assertEquals(List.of(), new MavenGoal("test", "").arguments());
    }

    private static MavenProjectController profiled(List<String> rootProfiles, List<String> coreProfiles) {
        MavenProjectController controller = new MavenProjectController();
        controller.finishLoading(new MavenProjectLoad.Loaded(profiledTree(rootProfiles, coreProfiles)));
        return controller;
    }

    private static MavenModule profiledTree(List<String> rootProfiles, List<String> coreProfiles) {
        MavenModule core = new MavenModule("core", "core", coreProfiles, List.of());
        return new MavenModule("vintelli", "", rootProfiles, List.of(core));
    }

    private static int indexOf(List<Row> rows, Predicate<Row> match) {
        for (int index = 0; index < rows.size(); index++) {
            if (match.test(rows.get(index))) {
                return index;
            }
        }
        throw new AssertionError("no matching row in " + rows);
    }

    private static Predicate<Row> profileOf(String selector, String id) {
        return row -> row.kind() == RowKind.PROFILE && selector.equals(row.selector()) && id.equals(row.label());
    }

    private static MavenProjectController loaded() {
        MavenProjectController controller = new MavenProjectController();
        controller.finishLoading(new MavenProjectLoad.Loaded(ROOT));
        return controller;
    }

    private static void selectModule(MavenProjectController controller, String label) {
        selectRow(controller, row -> row.kind() == RowKind.MODULE && label.equals(row.label()));
    }

    private static Predicate<Row> phaseOf(String selector, String phase) {
        return row -> row.kind() == RowKind.PHASE && selector.equals(row.selector()) && phase.equals(row.phase());
    }

    private static void selectRow(MavenProjectController controller, Predicate<Row> match) {
        List<Row> rows = controller.rows();
        for (int index = 0; index < rows.size(); index++) {
            if (match.test(rows.get(index))) {
                controller.select(index);
                return;
            }
        }
        throw new AssertionError("no matching row in " + rows);
    }

    private static Row selectedRow(MavenProjectController controller) {
        return controller.rows().get(controller.selected());
    }
}
