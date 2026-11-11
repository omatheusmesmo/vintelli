package dev.omatheusmesmo.vintelli.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ApplicationRunDetectorTest {

    @TempDir
    Path root;

    @ParameterizedTest
    @CsvSource({"quarkus-maven-plugin,maven:quarkus:dev,quarkus:dev", "spring-boot-maven-plugin,maven:spring-boot:run,spring-boot:run"})
    void recognizesApplicationPluginsAndOffersStartBeforeBuildPhases(String plugin, String id, String goal) throws IOException {
        Files.writeString(root.resolve("pom.xml"), pom("<build><plugins><plugin><artifactId>" + plugin
                + "</artifactId></plugin></plugins></build>"));
        BuildToolDetector detector = new BuildToolDetector(new LocalProjectRoot(root));
        assertEquals(id, detector.specifications().getFirst().identifier());
        assertEquals(List.of("mvn", goal), detector.specifications().getFirst().command());
        assertTrue(detector.specification(id, List.of()).isPresent());
    }

    @Test
    void executableWrapperIsKeptForTheStartCommand() throws IOException {
        Files.writeString(root.resolve("pom.xml"), pom("<build><plugins><plugin><artifactId>quarkus-maven-plugin</artifactId></plugin></plugins></build>"));
        Path wrapper = Files.writeString(root.resolve("mvnw"), "#!/bin/sh\nexit 0\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        BuildToolDetector detector = new BuildToolDetector(new LocalProjectRoot(root), false);
        assertEquals(List.of(root.toRealPath().resolve("mvnw").toString(), "quarkus:dev"), detector.specifications().getFirst().command());
    }

    @Test
    void plainLibrariesPluginManagementAndMalformedPomsDoNotInventAStartCommand() throws IOException {
        for (String descriptor : List.of(pom(""), "not xml", pom("<build><pluginManagement><plugins><plugin>"
                + "<artifactId>quarkus-maven-plugin</artifactId></plugin></plugins></pluginManagement></build>"),
                pom("<packaging>pom</packaging><build><plugins><plugin><artifactId>quarkus-maven-plugin</artifactId></plugin></plugins></build>"))) {
            Files.writeString(root.resolve("pom.xml"), descriptor);
            assertEquals("maven:clean", new BuildToolDetector(new LocalProjectRoot(root)).specifications().getFirst().identifier());
        }
    }

    @Test
    void execJavaRequiresAConfiguredMainClass() throws IOException {
        String plugin = "<build><plugins><plugin><artifactId>exec-maven-plugin</artifactId>%s</plugin></plugins></build>";
        Files.writeString(root.resolve("pom.xml"), pom(plugin.formatted("")));
        assertEquals("maven:clean", new BuildToolDetector(new LocalProjectRoot(root)).specifications().getFirst().identifier());
        Files.writeString(root.resolve("pom.xml"), pom(plugin.formatted("<configuration><mainClass>example.Main</mainClass></configuration>")));
        assertEquals("maven:exec:java", new BuildToolDetector(new LocalProjectRoot(root)).specifications().getFirst().identifier());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "plugins { id 'io.quarkus' }|gradle:quarkusDev",
            "plugins { id(\"org.springframework.boot\") version \"3.0\" }|gradle:bootRun",
            "plugins { application }|gradle:run",
            "apply plugin: 'application'|gradle:run",
            "apply(plugin = \"io.quarkus\")|gradle:quarkusDev"
    })
    void recognizesDeclarativeGradlePluginsWithoutEvaluatingBuilds(String build, String id) throws IOException {
        Files.writeString(root.resolve("build.gradle.kts"), build);
        assertEquals(id, new BuildToolDetector(new LocalProjectRoot(root)).specifications().getFirst().identifier());
    }

    @Test
    void gradleLibrariesAndCommentedPluginsKeepOnlyLifecycleTasks() throws IOException {
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'com.android.application'; id 'io.quarkus' apply false }\n"
                + "// id 'io.quarkus'\n/* id 'org.springframework.boot' */\nval example = \"id 'io.quarkus'\"");
        assertEquals("gradle:clean", new BuildToolDetector(new LocalProjectRoot(root)).specifications().getFirst().identifier());
    }

    @Test
    void doctypeAndOversizedDescriptorsAreNotReadAsApplications() throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<!DOCTYPE project [<!ENTITY external SYSTEM 'file:///not-read'>]>" + pom(""));
        assertEquals("maven:clean", new BuildToolDetector(new LocalProjectRoot(root)).specifications().getFirst().identifier());
        Files.writeString(root.resolve("pom.xml"), " ".repeat(2 * 1024 * 1024 + 1));
        assertEquals("maven:clean", new BuildToolDetector(new LocalProjectRoot(root)).specifications().getFirst().identifier());
    }

    private static String pom(String body) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>app</artifactId><version>1</version>"
                + body + "</project>";
    }
}
