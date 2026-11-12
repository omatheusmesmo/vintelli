package dev.omatheusmesmo.vintelli.local;

import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraph;
import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraph.LibraryDependency;
import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraph.Disposition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenProjectDependencyGraphProviderTest {

    @TempDir
    Path temp;

    @Test
    void resolvesCachedTransitivesAndMarksTheGraphComplete() throws IOException {
        Path project = Files.createDirectory(temp.resolve("project"));
        Path repository = Files.createDirectory(temp.resolve("repository"));
        write(project.resolve("pom.xml"), pom("demo", "<dependencies>"
                + "<dependency><groupId>example</groupId><artifactId>direct</artifactId><version>1.0</version>"
                + "<scope>compile</scope></dependency></dependencies>"));
        artifact(repository, "example", "direct", "1.0", "<dependency><groupId>example</groupId>"
                + "<artifactId>transitive</artifactId><version>2.0</version><scope>runtime</scope></dependency>");
        artifact(repository, "example", "transitive", "2.0", "");

        ProjectDependencyGraph graph = new MavenProjectDependencyGraphProvider(repository).read(project);

        assertTrue(graph.complete(), graph.message() + " " + graph.modules().getFirst().dependencies());
        LibraryDependency direct = graph.modules().getFirst().dependencies().getFirst();
        assertEquals("direct", direct.artifactId());
        assertEquals("compile", direct.scope());
        assertEquals(Disposition.RESOLVED, direct.disposition());
        assertEquals(repository.resolve("example/direct/1.0/direct-1.0.jar"), direct.jar());
        LibraryDependency transitive = direct.children().getFirst();
        assertEquals("transitive", transitive.artifactId());
        assertEquals("runtime", transitive.scope());
        assertEquals(Disposition.RESOLVED, transitive.disposition());
    }

    @Test
    void includesOptionalDirectDependenciesAndIdentifiesThemAsOptional() throws IOException {
        Path project = Files.createDirectory(temp.resolve("project"));
        Path repository = Files.createDirectory(temp.resolve("repository"));
        write(project.resolve("pom.xml"), pom("demo", "<dependencies><dependency>"
                + "<groupId>example</groupId><artifactId>optional-library</artifactId><version>1.0</version>"
                + "<optional>true</optional></dependency></dependencies>"));
        artifact(repository, "example", "optional-library", "1.0", dependency("optional-child", "1.0"));
        artifact(repository, "example", "optional-child", "1.0", "");

        ProjectDependencyGraph graph = new MavenProjectDependencyGraphProvider(repository).read(project);

        assertTrue(graph.complete(), graph.message());
        LibraryDependency optional = graph.modules().getFirst().dependencies().getFirst();
        assertEquals("optional-library", optional.artifactId());
        assertTrue(optional.optional());
        assertEquals(Disposition.RESOLVED, optional.disposition());
        assertTrue(optional.children().isEmpty(), "optional dependency transitives are not propagated");
    }

    @Test
    void keepsArtifactsWithDifferentClassifiersAsIndependentConflicts() throws IOException {
        Path project = Files.createDirectory(temp.resolve("project"));
        Path repository = Files.createDirectory(temp.resolve("repository"));
        write(project.resolve("pom.xml"), pom("demo", "<dependencies>"
                + dependency("variant", "1.0")
                + "<dependency><groupId>example</groupId><artifactId>variant</artifactId><version>2.0</version>"
                + "<type>zip</type><classifier>bin</classifier></dependency></dependencies>"));
        artifact(repository, "example", "variant", "1.0", "");
        artifact(repository, "example", "variant", "2.0", "");
        write(repository.resolve("example/variant/2.0/variant-2.0-bin.zip"), "binary archive");

        ProjectDependencyGraph graph = new MavenProjectDependencyGraphProvider(repository).read(project);

        assertTrue(graph.complete(), graph.message() + " " + graph.modules().getFirst().dependencies());
        List<LibraryDependency> variants = graph.modules().getFirst().dependencies();
        assertEquals(2, variants.size(), variants.toString());
        assertTrue(variants.stream().allMatch(dependency -> dependency.disposition() == Disposition.RESOLVED),
                variants.toString());
        assertEquals(List.of("1.0", "2.0"), variants.stream().map(LibraryDependency::version).toList());
    }

    @Test
    void preservesDirectDependenciesAndReportsMissingOfflineMetadata() throws IOException {
        Path project = Files.createDirectory(temp.resolve("project"));
        Path repository = Files.createDirectory(temp.resolve("repository"));
        write(project.resolve("pom.xml"), pom("demo", "<dependencies><dependency><groupId>example</groupId>"
                + "<artifactId>uncached</artifactId><version>1.0</version><scope>test</scope></dependency></dependencies>"));

        ProjectDependencyGraph graph = new MavenProjectDependencyGraphProvider(repository).read(project);

        assertFalse(graph.complete());
        assertTrue(graph.message().contains("Incomplete"));
        LibraryDependency missing = graph.modules().getFirst().dependencies().getFirst();
        assertEquals("uncached", missing.artifactId());
        assertEquals("test", missing.scope());
        assertEquals(Disposition.MISSING, missing.disposition());
        assertTrue(missing.jar() == null);
    }

    @Test
    void rejectsModulePathsThatEscapeTheProjectRoot() throws IOException {
        Path project = Files.createDirectory(temp.resolve("project"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        write(project.resolve("pom.xml"), pom("demo", "<modules><module>../outside</module></modules>"));
        write(outside.resolve("pom.xml"), pom("outside", ""));

        ProjectDependencyGraph graph = new MavenProjectDependencyGraphProvider(temp.resolve("repository")).read(project);

        assertFalse(graph.complete());
        assertEquals(1, graph.modules().size());
        assertTrue(graph.message().contains("escapes"));
    }

    @Test
    void marksCachedMetadataWithAMissingJarAsIncomplete() throws IOException {
        Path project = Files.createDirectory(temp.resolve("project"));
        Path repository = Files.createDirectory(temp.resolve("repository"));
        write(project.resolve("pom.xml"), pom("demo", "<dependencies><dependency><groupId>example</groupId>"
                + "<artifactId>jarless</artifactId><version>1.0</version></dependency></dependencies>"));
        Path artifactDirectory = repository.resolve("example/jarless/1.0");
        write(artifactDirectory.resolve("jarless-1.0.pom"), pom("jarless", "1.0", ""));

        ProjectDependencyGraph graph = new MavenProjectDependencyGraphProvider(repository).read(project);

        assertFalse(graph.complete());
        assertEquals(Disposition.MISSING, graph.modules().getFirst().dependencies().getFirst().disposition());
    }

    @Test
    void marksImportedBomAndProfileDependenciesAsIncomplete() throws IOException {
        Path project = Files.createDirectory(temp.resolve("project"));
        Path repository = Files.createDirectory(temp.resolve("repository"));
        String inputs = "<dependencyManagement><dependencies><dependency><groupId>example</groupId>"
                + "<artifactId>versions</artifactId><version>1.0</version><type>pom</type><scope>import</scope>"
                + "</dependency></dependencies></dependencyManagement>"
                + "<profiles><profile><id>optional-profile</id><dependencies><dependency>"
                + "<groupId>example</groupId><artifactId>profile-only</artifactId><version>1.0</version>"
                + "</dependency></dependencies></profile></profiles>";
        write(project.resolve("pom.xml"), pom("demo", inputs));

        ProjectDependencyGraph graph = new MavenProjectDependencyGraphProvider(repository).read(project);

        assertFalse(graph.complete());
        assertTrue(graph.message().contains("Incomplete"));
    }

    @Test
    void resolvesVersionlessModuleDependenciesFromAnOfflineImportedBom() throws IOException {
        Path project = Files.createDirectory(temp.resolve("project"));
        Path module = Files.createDirectory(project.resolve("feature"));
        Path repository = Files.createDirectory(temp.resolve("repository"));
        String bom = "<dependencyManagement><dependencies><dependency><groupId>example</groupId>"
                + "<artifactId>versions</artifactId><version>1.0</version><type>pom</type><scope>import</scope>"
                + "</dependency></dependencies></dependencyManagement>";
        write(project.resolve("pom.xml"), pom("demo", "<modules><module>feature</module></modules>" + bom));
        write(module.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><parent>"
                + "<groupId>example</groupId><artifactId>demo</artifactId><version>1.0</version></parent>"
                + "<artifactId>feature</artifactId><dependencies><dependency><groupId>example</groupId>"
                + "<artifactId>managed-lib</artifactId></dependency></dependencies></project>");
        write(repository.resolve("example/versions/1.0/versions-1.0.pom"),
                pom("versions", "1.0", "<dependencyManagement><dependencies><dependency>"
                        + "<groupId>example</groupId><artifactId>managed-lib</artifactId><version>3.2</version>"
                        + "</dependency></dependencies></dependencyManagement>"));
        artifact(repository, "example", "managed-lib", "3.2", "");

        Path configuredRepository = repository;
        Path repositoryAlias = temp.resolve("repository-alias");
        try {
            Files.createSymbolicLink(repositoryAlias, repository);
            configuredRepository = repositoryAlias;
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            // Some Windows runners do not grant symlink creation; the normal local path is still exercised.
        }

        ProjectDependencyGraph graph = new MavenProjectDependencyGraphProvider(configuredRepository).read(project);

        assertTrue(graph.complete(), graph.message());
        ProjectDependencyGraph.Module feature = graph.modules().stream()
                .filter(candidate -> candidate.name().equals("feature")).findFirst().orElseThrow();
        LibraryDependency managed = feature.dependencies().getFirst();
        assertEquals("managed-lib", managed.artifactId());
        assertEquals("3.2", managed.version());
        assertEquals(Disposition.RESOLVED, managed.disposition());
    }

    @Test
    void resolvesDependencyManagementFromAnActiveProfile() throws IOException {
        Path project = Files.createDirectory(temp.resolve("project"));
        Path repository = Files.createDirectory(temp.resolve("repository"));
        String profile = "<profiles><profile><id>local-default</id><activation><activeByDefault>true</activeByDefault>"
                + "</activation><dependencyManagement><dependencies><dependency><groupId>example</groupId>"
                + "<artifactId>profile-managed</artifactId><version>5.1</version></dependency></dependencies>"
                + "</dependencyManagement></profile></profiles>";
        String dependency = "<dependencies><dependency><groupId>example</groupId>"
                + "<artifactId>profile-managed</artifactId></dependency></dependencies>";
        write(project.resolve("pom.xml"), pom("demo", profile + dependency));
        artifact(repository, "example", "profile-managed", "5.1", "");

        ProjectDependencyGraph graph = new MavenProjectDependencyGraphProvider(repository).read(project);

        assertTrue(graph.complete(), graph.message());
        LibraryDependency managed = graph.modules().getFirst().dependencies().getFirst();
        assertEquals("5.1", managed.version());
        assertEquals(Disposition.RESOLVED, managed.disposition());
    }

    @Test
    void retainsVersionConflictsAsOmittedNodes() throws IOException {
        Path project = Files.createDirectory(temp.resolve("project"));
        Path repository = Files.createDirectory(temp.resolve("repository"));
        write(project.resolve("pom.xml"), pom("demo", "<dependencies>"
                + dependency("left", "1.0") + dependency("right", "1.0") + "</dependencies>"));
        artifact(repository, "example", "left", "1.0", dependency("shared", "1.0"));
        artifact(repository, "example", "right", "1.0", dependency("shared", "2.0"));
        artifact(repository, "example", "shared", "1.0", "");
        artifact(repository, "example", "shared", "2.0", "");

        ProjectDependencyGraph graph = new MavenProjectDependencyGraphProvider(repository).read(project);

        assertTrue(graph.complete(), graph.message());
        assertTrue(allDependencies(graph).stream().anyMatch(dependency -> dependency.disposition() == Disposition.OMITTED),
                allDependencies(graph).toString());
    }

    private static List<LibraryDependency> allDependencies(ProjectDependencyGraph graph) {
        List<LibraryDependency> result = new java.util.ArrayList<>();
        for (ProjectDependencyGraph.Module module : graph.modules()) {
            collect(module.dependencies(), result);
        }
        return result;
    }

    private static void collect(List<LibraryDependency> dependencies, List<LibraryDependency> result) {
        for (LibraryDependency dependency : dependencies) {
            result.add(dependency);
            collect(dependency.children(), result);
        }
    }

    private static String dependency(String artifact, String version) {
        return "<dependency><groupId>example</groupId><artifactId>" + artifact + "</artifactId><version>"
                + version + "</version></dependency>";
    }

    private static void artifact(Path repository, String group, String artifact, String version, String dependencies)
            throws IOException {
        Path directory = repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        String body = dependencies.isBlank() ? "" : "<dependencies>" + dependencies + "</dependencies>";
        write(directory.resolve(artifact + "-" + version + ".pom"), pom(artifact, version, body));
        write(directory.resolve(artifact + "-" + version + ".jar"), "jar");
    }

    private static String pom(String artifact, String body) {
        return pom(artifact, "1.0", body);
    }

    private static String pom(String artifact, String version, String body) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId>"
                + "<artifactId>" + artifact + "</artifactId><version>" + version + "</version>"
                + "<packaging>pom</packaging>"
                + body + "</project>";
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
