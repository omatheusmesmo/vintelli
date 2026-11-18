package dev.omatheusmesmo.vintelli.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.HoverCapability.Documentation;

/**
 * Documentation through the generic client and a real fake server process: the rich markdown the server sends
 * arrives unchanged, an empty hover is empty documentation, the document the editor holds is sent before the
 * request, and a server that cannot start fails the request explicitly.
 */
class LspHoverTest {

    private static final String MARKER = "hover-marker";
    private static final int RICH_LINE = FakeLspServer.RICH_HOVER_LINE;
    private static final int EMPTY_LINE = FakeLspServer.EMPTY_HOVER_LINE;
    private static final int BUFFER_LINE = FakeLspServer.BUFFER_HOVER_LINE;

    @TempDir
    Path workspace;

    @Test
    void theMarkdownTheServerSendsArrivesUnchanged() throws Exception {
        Path traceFile = trace("rich");
        Files.writeString(document(), "class Fake {}");
        try (LspClient client = new LspClient(config(fakeCommand(traceFile)))) {
            String markdown = ok(client.hover(document(), "class Fake {}", RICH_LINE, 0));

            assertEquals(FakeLspServer.RICH_HOVER, markdown);
            assertTrue(markdown.contains("int String.length()"), markdown);
        }
        assertTraceContains(traceFile, "hover");
    }

    @Test
    void aServerWithoutAHoverGivesEmptyDocumentation() throws Exception {
        Path traceFile = trace("empty");
        Files.writeString(document(), "class Fake {}");
        try (LspClient client = new LspClient(config(fakeCommand(traceFile)))) {
            assertEquals("", ok(client.hover(document(), "class Fake {}", EMPTY_LINE, 0)));
        }
    }

    @Test
    void theDocumentTheEditorHoldsIsSentBeforeTheHoverRequest() throws Exception {
        Path traceFile = trace("buffer");
        Files.writeString(document(), "class Fake {}");
        try (LspClient client = new LspClient(config(fakeCommand(traceFile)))) {
            String unsaved = "class Fake { int " + FakeLspServer.UNSAVED_MARKER + "; }";

            assertEquals("buffer unsaved", ok(client.hover(document(), unsaved, BUFFER_LINE, 0)));
            assertEquals("buffer disk", ok(client.hover(document(), "class Fake {}", BUFFER_LINE, 0)),
                    "a later buffer replaces the one the server holds");
        }
        String trace = Files.readString(traceFile);
        assertTrue(trace.indexOf("didChange") < trace.lastIndexOf("hover"), trace);
    }

    @Test
    void aServerThatCannotStartFailsTheRequestExplicitly() throws Exception {
        Path missing = workspace.resolve("no-such-language-server");
        try (LspClient client = new LspClient(config(List.of(missing.toString())))) {
            CapabilityResult<String> result = client.hover(document(), "class Fake {}", 0, 0);

            assertTrue(result.isFailure(), result.toString());
            assertFalse(result.failureOf().orElseThrow().reason().isBlank());
        }
    }

    @Test
    void theProviderExposesTheDocumentationOfTheActiveProfile() throws Exception {
        Path traceFile = trace("provider");
        Files.writeString(document(), "class Fake {}");
        try (LspClient client = new LspClient(config(fakeCommand(traceFile)))) {
            LspHoverProvider provider = new LspHoverProvider(client);

            assertEquals("unit", provider.identifier());
            Documentation documentation = ok(provider.hover(document(), "class Fake {}", RICH_LINE, 0));

            assertEquals(FakeLspServer.RICH_HOVER, documentation.text());
            assertEquals("", ok(provider.hover(document(), "class Fake {}", EMPTY_LINE, 0)).text());
        }
    }

    private Path document() {
        return workspace.resolve("Fake.java");
    }

    private Path trace(String name) throws IOException {
        Path trace = workspace.resolve(name + ".trace");
        Files.writeString(trace, "");
        return trace;
    }

    private static void assertTraceContains(Path traceFile, String... methods) throws IOException {
        String trace = Files.readString(traceFile);
        for (String method : methods) {
            assertTrue(trace.contains(method + "\n"), "the trace lacks " + method + ":\n" + trace);
        }
    }

    private List<String> fakeCommand(Path traceFile) throws IOException {
        try {
            List<String> command = new ArrayList<>();
            command.add(ProcessHandle.current().info().command().orElse("java"));
            command.add("-cp");
            command.add(FakeLspServer.classpath());
            command.add(FakeLspServer.class.getName());
            command.add("--trace");
            command.add(traceFile.toAbsolutePath().toString());
            command.add("--marker");
            command.add(MARKER);
            return command;
        } catch (URISyntaxException failure) {
            throw new IOException("cannot locate the fake server classpath", failure);
        }
    }

    private LspProfileConfig config(List<String> command) {
        LspProfileConfig.Profile profile = new StaticProfileConfig.StaticProfile(command,
                Optional.of(workspace.toString()), Optional.empty());
        return new StaticProfileConfig.StaticConfig(Optional.empty(), Map.of("unit", profile));
    }

    private static <V> V ok(CapabilityResult<V> result) {
        return ((CapabilityResult.Ok<V>) result).value();
    }
}
