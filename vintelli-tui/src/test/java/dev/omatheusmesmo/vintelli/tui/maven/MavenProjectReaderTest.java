package dev.omatheusmesmo.vintelli.tui.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenProjectReaderTest {

    @TempDir
    Path root;

    @Test
    void readsTheModuleTreeRecursivelyWithSelectorsRelativeToTheRoot() throws IOException {
        pom(root, "<artifactId>parent</artifactId><modules><module>api</module><module>core</module></modules>");
        pom(root.resolve("api"), "<artifactId>vintelli-api</artifactId>");
        pom(root.resolve("core"), "<artifactId>core</artifactId><modules><module>runtime</module></modules>");
        pom(root.resolve("core/runtime"), "<artifactId>runtime</artifactId>");

        MavenModule tree = loaded(MavenProjectReader.read(root));

        assertEquals("parent", tree.artifactId());
        assertEquals("", tree.selector());
        assertEquals(List.of("vintelli-api", "core"), tree.modules().stream().map(MavenModule::artifactId).toList());
        assertEquals(List.of("api", "core"), tree.modules().stream().map(MavenModule::selector).toList());
        MavenModule core = tree.modules().get(1);
        assertEquals(List.of("runtime"), core.modules().stream().map(MavenModule::artifactId).toList());
        assertEquals("core/runtime", core.modules().getFirst().selector());
    }

    @Test
    void aProjectWithoutModulesIsASingleRootNode() throws IOException {
        pom(root, "<artifactId>single</artifactId>");

        MavenModule tree = loaded(MavenProjectReader.read(root));

        assertEquals("single", tree.artifactId());
        assertEquals(List.of(), tree.modules());
    }

    @Test
    void listsTheProfileIdsOfEachModuleInPomOrder() throws IOException {
        pom(root, "<artifactId>parent</artifactId><modules><module>api</module></modules>"
                + "<profiles><profile><id>release</id></profile><profile><id>fast</id></profile></profiles>");
        pom(root.resolve("api"), "<artifactId>api</artifactId><profiles><profile><id>native</id></profile></profiles>");

        MavenModule tree = loaded(MavenProjectReader.read(root));

        assertEquals(List.of("release", "fast"), tree.profiles());
        assertEquals(List.of("native"), tree.modules().getFirst().profiles());
    }

    @Test
    void aPomWithoutProfilesListsNone() throws IOException {
        pom(root, "<artifactId>single</artifactId>");

        MavenModule tree = loaded(MavenProjectReader.read(root));

        assertEquals(List.of(), tree.profiles());
    }

    @Test
    void aProfileWithoutAnIdIsSkippedAndADuplicateIdIsListedOnce() throws IOException {
        pom(root, "<artifactId>single</artifactId><profiles>"
                + "<profile><activation><activeByDefault>true</activeByDefault></activation></profile>"
                + "<profile><id>  </id></profile>"
                + "<profile><id>ci</id></profile><profile><id>docs</id></profile><profile><id>ci</id></profile>"
                + "</profiles>");

        MavenModule tree = loaded(MavenProjectReader.read(root));

        assertEquals(List.of("ci", "docs"), tree.profiles());
    }

    @Test
    void aDoctypePomWithProfilesIsStillRefused() throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<!DOCTYPE project [<!ENTITY leak SYSTEM \"file:///etc/hostname\">]>"
                + "<project><artifactId>p</artifactId>"
                + "<profiles><profile><id>&leak;</id></profile></profiles></project>", StandardCharsets.UTF_8);

        MavenProjectLoad load = MavenProjectReader.read(root);

        assertInstanceOf(MavenProjectLoad.Unreadable.class, load);
    }

    @Test
    void aMissingPomIsReportedAsMissingNotAsAnEmptyTree() {
        assertInstanceOf(MavenProjectLoad.Missing.class, MavenProjectReader.read(root));
    }

    @Test
    void aMalformedPomIsReportedWithItsReason() throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project><modules>", StandardCharsets.UTF_8);

        MavenProjectLoad load = MavenProjectReader.read(root);

        MavenProjectLoad.Unreadable unreadable = assertInstanceOf(MavenProjectLoad.Unreadable.class, load);
        assertTrue(unreadable.reason().startsWith("cannot parse"), unreadable.reason());
    }

    @Test
    void aDoctypeIsRefusedSoAnExternalEntityNeverReachesTheTree() throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<!DOCTYPE project [<!ENTITY leak SYSTEM \"file:///etc/hostname\">]>"
                + "<project><artifactId>&leak;</artifactId></project>", StandardCharsets.UTF_8);

        MavenProjectLoad load = MavenProjectReader.read(root);

        assertInstanceOf(MavenProjectLoad.Unreadable.class, load);
    }

    @Test
    void aModuleOutsideTheProjectRootIsRefused() throws IOException {
        pom(root.resolve("outside"), "<artifactId>x</artifactId>");
        Path project = root.resolve("project");
        pom(project, "<artifactId>p</artifactId><modules><module>../outside</module></modules>");

        MavenProjectLoad load = MavenProjectReader.read(project);

        MavenProjectLoad.Unreadable unreadable = assertInstanceOf(MavenProjectLoad.Unreadable.class, load);
        assertTrue(unreadable.reason().contains("outside the project root"), unreadable.reason());
    }

    @Test
    void aModuleThatListsItsParentAgainCannotLoopForever() throws IOException {
        pom(root, "<artifactId>p</artifactId><modules><module>loop</module></modules>");
        pom(root.resolve("loop"), "<artifactId>loop</artifactId><modules><module>..</module></modules>");

        MavenProjectLoad load = MavenProjectReader.read(root);

        MavenProjectLoad.Unreadable unreadable = assertInstanceOf(MavenProjectLoad.Unreadable.class, load);
        assertTrue(unreadable.reason().contains("listed twice"), unreadable.reason());
    }

    @Test
    void aListedModuleWithoutAPomIsReported() throws IOException {
        pom(root, "<artifactId>p</artifactId><modules><module>gone</module></modules>");

        MavenProjectLoad load = MavenProjectReader.read(root);

        assertInstanceOf(MavenProjectLoad.Unreadable.class, load);
        assertFalse(((MavenProjectLoad.Unreadable) load).reason().isBlank());
    }

    private static void pom(Path directory, String body) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("pom.xml"), "<project>" + body + "</project>", StandardCharsets.UTF_8);
    }

    private static MavenModule loaded(MavenProjectLoad load) {
        return assertInstanceOf(MavenProjectLoad.Loaded.class, load).root();
    }
}
