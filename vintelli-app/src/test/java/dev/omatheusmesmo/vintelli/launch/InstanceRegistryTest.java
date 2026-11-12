package dev.omatheusmesmo.vintelli.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.omatheusmesmo.vintelli.launch.InstanceRegistry.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceRegistryTest {

    private static final long LIVE_PID = 4242;
    private static final long STALE_PID = 4343;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishWritesThePidPortHostAndRootAndNoTemporaryFile(@TempDir Path work) throws IOException {
        Path root = Files.createDirectories(work.resolve("project"));
        InstanceRegistry registry = registry(work, pid -> pid == LIVE_PID);

        registry.publish(new Instance(LIVE_PID, 43123, "127.0.0.1", root));

        JsonNode record = mapper.readTree(instances(work).resolve(LIVE_PID + ".json").toFile());
        assertEquals(LIVE_PID, record.get("pid").asLong());
        assertEquals(43123, record.get("port").asInt());
        assertEquals("127.0.0.1", record.get("host").asText());
        assertEquals(InstanceRegistry.canonical(root).toString(), record.get("projectRoot").asText());
        assertEquals(List.of(LIVE_PID + ".json"), fileNames(instances(work)));
    }

    @Test
    void publishReplacesTheRecordOfTheSameProcess(@TempDir Path work) throws IOException {
        Path root = Files.createDirectories(work.resolve("project"));
        InstanceRegistry registry = registry(work, pid -> pid == LIVE_PID);

        registry.publish(new Instance(LIVE_PID, 43123, "127.0.0.1", root));
        registry.publish(new Instance(LIVE_PID, 43999, "127.0.0.1", root));

        assertEquals(43999, registry.findByProjectRoot(root).orElseThrow().port());
        assertEquals(List.of(LIVE_PID + ".json"), fileNames(instances(work)));
    }

    @Test
    void recordsAreOwnerOnlyWhereThePlatformHasPermissions(@TempDir Path work) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path root = Files.createDirectories(work.resolve("project"));
        InstanceRegistry registry = registry(work, pid -> pid == LIVE_PID);

        registry.publish(new Instance(LIVE_PID, 43123, "127.0.0.1", root));

        Path record = instances(work).resolve(LIVE_PID + ".json");
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(record));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(instances(work)));
    }

    @Test
    void findByProjectRootReturnsTheLiveInstanceThatOwnsTheRoot(@TempDir Path work) throws IOException {
        Path first = Files.createDirectories(work.resolve("first"));
        Path second = Files.createDirectories(work.resolve("second"));
        long secondPid = LIVE_PID + 1;
        InstanceRegistry registry = registry(work, pid -> pid == LIVE_PID || pid == secondPid);
        registry.publish(new Instance(LIVE_PID, 43123, "127.0.0.1", first));
        registry.publish(new Instance(secondPid, 43124, "127.0.0.1", second));

        Instance owner = registry.findByProjectRoot(second).orElseThrow();

        assertEquals(secondPid, owner.pid());
        assertEquals(43124, owner.port());
        assertEquals("http://127.0.0.1:43124/mcp", owner.mcpUrl());
        assertEquals(LIVE_PID, registry.findByProjectRoot(first).orElseThrow().pid());
    }

    @Test
    void findByProjectRootMatchesAnySpellingOfTheSameDirectory(@TempDir Path work) throws IOException {
        Path root = Files.createDirectories(work.resolve("project"));
        Path spelledWithDot = root.resolve("..").resolve("project").resolve(".");
        InstanceRegistry registry = registry(work, pid -> pid == LIVE_PID);
        registry.publish(new Instance(LIVE_PID, 43123, "127.0.0.1", root));

        assertEquals(LIVE_PID, registry.findByProjectRoot(spelledWithDot).orElseThrow().pid());
    }

    @Test
    void publishStoresTheRealPathOfARootGivenThroughASymbolicLink(@TempDir Path work) throws IOException {
        Path root = Files.createDirectories(work.resolve("project"));
        Path link = work.resolve("link");
        try {
            Files.createSymbolicLink(link, root);
        } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            assumeTrue(false, "symbolic links are not available here");
        }
        InstanceRegistry registry = registry(work, pid -> pid == LIVE_PID);

        registry.publish(new Instance(LIVE_PID, 43123, "127.0.0.1", link));

        JsonNode record = mapper.readTree(instances(work).resolve(LIVE_PID + ".json").toFile());
        assertEquals(root.toRealPath().toString(), record.get("projectRoot").asText());
        assertEquals(LIVE_PID, registry.findByProjectRoot(root).orElseThrow().pid());
    }

    @Test
    void findByProjectRootIsEmptyForAnUnknownRoot(@TempDir Path work) throws IOException {
        Path root = Files.createDirectories(work.resolve("project"));
        InstanceRegistry registry = registry(work, pid -> pid == LIVE_PID);
        registry.publish(new Instance(LIVE_PID, 43123, "127.0.0.1", root));

        assertTrue(registry.findByProjectRoot(work.resolve("elsewhere")).isEmpty());
    }

    @Test
    void aRecordWhosePidIsGoneIsIgnoredAndDeletedWhenRead(@TempDir Path work) throws IOException {
        Path root = Files.createDirectories(work.resolve("project"));
        InstanceRegistry registry = registry(work, pid -> pid == LIVE_PID);
        writeRecord(work, STALE_PID, 43500, root);

        assertTrue(registry.findByProjectRoot(root).isEmpty());
        assertFalse(Files.exists(instances(work).resolve(STALE_PID + ".json")));
    }

    @Test
    void aRecordLeftByAnEarlierProcessWithTheSamePidIsReplaced(@TempDir Path work) throws IOException {
        Path root = Files.createDirectories(work.resolve("project"));
        InstanceRegistry registry = registry(work, pid -> pid == LIVE_PID);
        writeRecord(work, LIVE_PID, 43500, root);

        registry.publish(new Instance(LIVE_PID, 43777, "127.0.0.1", root));

        assertEquals(43777, registry.findByProjectRoot(root).orElseThrow().port());
    }

    @Test
    void aMalformedRecordIsIgnored(@TempDir Path work) throws IOException {
        Path root = Files.createDirectories(work.resolve("project"));
        InstanceRegistry registry = registry(work, pid -> pid == LIVE_PID);
        Files.createDirectories(instances(work));
        Files.writeString(instances(work).resolve("broken.json"), "{\"pid\": \"not a number\", \"port\": ");

        assertTrue(registry.findByProjectRoot(root).isEmpty());
    }

    @Test
    void retireRemovesOnlyTheRecordOfThisProcess(@TempDir Path work) throws IOException {
        Path root = Files.createDirectories(work.resolve("project"));
        long otherPid = LIVE_PID + 1;
        InstanceRegistry registry = registry(work, pid -> pid == LIVE_PID || pid == otherPid);
        registry.publish(new Instance(LIVE_PID, 43123, "127.0.0.1", root));
        registry.publish(new Instance(otherPid, 43124, "127.0.0.1", work.resolve("other")));

        registry.retire(LIVE_PID);

        assertEquals(List.of(otherPid + ".json"), fileNames(instances(work)));
    }

    @Test
    void theDefaultLivenessCheckRecognisesTheRunningProcessOnly() throws IOException, InterruptedException {
        assertTrue(InstanceRegistry.isAlive(ProcessHandle.current().pid()));
        Process exited = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        exited.waitFor();
        assertFalse(InstanceRegistry.isAlive(exited.pid()));
    }

    @Test
    void linuxStateDirectoryFollowsXdgStateHome() {
        Path state = Path.of("/var/state").toAbsolutePath();
        Path directory = InstanceRegistry.defaultDirectory("Linux", "/home/ana",
                Map.of("XDG_STATE_HOME", state.toString()));
        assertEquals(state.resolve("vintelli").resolve("instances"), directory);
    }

    @Test
    void linuxStateDirectoryFallsBackWhenXdgStateHomeIsRelative() {
        Path directory = InstanceRegistry.defaultDirectory("Linux", "/home/ana", Map.of("XDG_STATE_HOME", "state"));
        assertEquals(Path.of("/home/ana", ".local", "state", "vintelli", "instances"), directory);
    }

    @Test
    void windowsStateDirectoryUsesLocalAppData() {
        Path directory = InstanceRegistry.defaultDirectory("Windows 11", "C:/Users/ana",
                Map.of("LOCALAPPDATA", "C:/Users/ana/AppData/Local"));
        assertEquals(Path.of("C:/Users/ana/AppData/Local", "vintelli", "instances"), directory);
    }

    @Test
    void macStateDirectoryUsesApplicationSupport() {
        Path directory = InstanceRegistry.defaultDirectory("Mac OS X", "/Users/ana", Map.of());
        assertEquals(Path.of("/Users/ana", "Library", "Application Support", "vintelli", "instances"), directory);
    }

    @Test
    void aWildcardBindIsReachedOnTheLoopback() {
        assertEquals("127.0.0.1", InstanceRegistry.reachableHost("0.0.0.0"));
        assertEquals("::1", InstanceRegistry.reachableHost("::"));
        assertEquals("127.0.0.1", InstanceRegistry.reachableHost("127.0.0.1"));
    }

    @Test
    void anIpv6HostIsBracketedInTheUrl() {
        assertEquals("http://[::1]:8080/mcp", new Instance(LIVE_PID, 8080, "::1", Path.of("/p")).mcpUrl());
    }

    private static InstanceRegistry registry(Path work, LongPredicate alive) {
        return new InstanceRegistry(instances(work), new ObjectMapper(), alive);
    }

    private static Path instances(Path work) {
        return work.resolve("state").resolve("vintelli").resolve("instances");
    }

    private void writeRecord(Path work, long pid, int port, Path root) throws IOException {
        Files.createDirectories(instances(work));
        String json = mapper.createObjectNode()
                .put("pid", pid)
                .put("port", port)
                .put("host", "127.0.0.1")
                .put("projectRoot", InstanceRegistry.canonical(root).toString())
                .toString();
        Files.writeString(instances(work).resolve(pid + ".json"), json);
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }
}
