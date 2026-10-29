import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Minimal hand-rolled JSON-RPC LSP client for the spike 47 native benchmark,
 * the Option B side of the comparison defined in
 * docs/spikes/spike-23-lsp4j-vs-own-jsonrpc-client.md.
 *
 * Runs the exact 0.1.0 message flow against an echo server: initialize,
 * initialized, one textDocument/hover request, shutdown, exit. No external
 * dependencies: JSON is parsed by a small tree parser and responses are
 * decoded into records with hand-written field mapping, mirroring what
 * vintelli-jsonrpc (#19) would need. Records only, no reflection.
 *
 * Two modes, both with the same summary line (handrolled <init> <hover>
 * <total> ok):
 * - stdin/stdout mode, for an externally launched server:
 *   java handrolled-minimal-client.java
 * - spawn mode, for quick manual runs: the client starts the server itself
 *   and pipes stdio to it:
 *   java handrolled-minimal-client.java <echo-server-program> [args...]
 * In externally-piped tests the parent shell wires two named pipes, so session
 * timings exclude any in-client server spawn cost.
 */
final class HandrolledJsonRpcClient {

    private static final long TIMEOUT_S = 60L;

    record Position(int line, int character) {}

    record TextDocumentIdentifier(String uri) {}

    record HoverParams(TextDocumentIdentifier textDocument, Position position) {}

    record ServerCapabilities(int textDocumentSync, boolean hoverProvider) {}

    record InitializeResult(ServerCapabilities capabilities, String serverName) {}

    record MarkupContent(String kind, String value) {}

    record Hover(MarkupContent contents) {}

    public static void main(String[] args) throws Exception {
        Process process = null;
        Transport transport;
        if (args.length < 1) {
            transport = new Transport(
                    new BufferedInputStream(System.in),
                    new BufferedOutputStream(System.out));
        } else {
            List<String> serverCommand = new ArrayList<>();
            serverCommand.add(args[0]);
            for (int i = 1; i < args.length; i++) {
                serverCommand.add(args[i]);
            }
            process = new ProcessBuilder(serverCommand).start();
            transport = new Transport(
                    new BufferedInputStream(process.getInputStream()),
                    new BufferedOutputStream(process.getOutputStream()));
        }

        try {
            runSession(transport, process);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static void runSession(Transport transport, Process process) throws Exception {
        long totalStart = System.nanoTime();
        int id = 1;
        Map<String, Object> initResponse = transport.request(id++,
                "initialize",
                "{\"processId\":null,\"capabilities\":{},"
                + "\"rootUri\":\"file:///mnt/spike-sandbox\"}");
        InitializeResult initialized = decodeInitializeResult(initResponse);
        validateCapabilities(initialized);

        long initNanos = System.nanoTime() - totalStart;

        transport.notify("initialized", "{}");

        long hoverStart = System.nanoTime();
        Map<String, Object> hoverResponse = transport.request(id++,
                "textDocument/hover",
                "{\"textDocument\":{\"uri\":\"file:///src/A.java\"},"
                + "\"position\":{\"line\":0,\"character\":0}}");
        Hover hover = decodeHover(hoverResponse);
        validateHover(hover);
        long hoverNanos = System.nanoTime() - hoverStart;

        transport.request(id++, "shutdown", null);
        transport.notify("exit", null);
        if (process != null && !process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("echo server did not exit");
        }

        long totalNanos = System.nanoTime() - totalStart;
        System.err.printf("handrolled %d %d %d ok%n",
                TimeUnit.NANOSECONDS.toMillis(initNanos),
                TimeUnit.NANOSECONDS.toMillis(hoverNanos),
                TimeUnit.NANOSECONDS.toMillis(totalNanos));
    }

    private static void validateCapabilities(InitializeResult result) {
        if (result.serverName() == null || result.capabilities() == null) {
            throw new IllegalStateException("initialize result incomplete");
        }
    }

    private static void validateHover(Hover hover) {
        if (hover.contents() == null || hover.contents().value() == null) {
            throw new IllegalStateException("hover result incomplete");
        }
    }

    private static InitializeResult decodeInitializeResult(Map<String, Object> response) {
        Object result = response.get("result");
        if (result == null) {
            throw new IllegalStateException("initialize returned no result");
        }
        Map<String, Object> map = JsonExpect.object(result);
        Map<String, Object> capabilities = JsonExpect.object(map.get("capabilities"));
        int textDocumentSync = capabilities.get("textDocumentSync") instanceof Number n
                ? n.intValue() : -1;
        boolean hoverProvider = Boolean.TRUE.equals(capabilities.get("hoverProvider"));
        Map<String, Object> serverInfo = map.get("serverInfo") instanceof Map<?, ?> raw
                ? JsonExpect.map(raw) : Map.of();
        return new InitializeResult(
                new ServerCapabilities(textDocumentSync, hoverProvider),
                (String) serverInfo.get("name"));
    }

    private static Hover decodeHover(Map<String, Object> response) {
        Object result = response.get("result");
        if (result == null) {
            throw new IllegalStateException("hover returned no result");
        }
        Map<String, Object> map = JsonExpect.object(result);
        Object contents = map.get("contents");
        if (contents instanceof Map<?, ?> raw) {
            Map<String, Object> content = JsonExpect.map(raw);
            return new Hover(new MarkupContent(
                    (String) content.get("kind"), (String) content.get("value")));
        }
        return new Hover(new MarkupContent("plaintext", String.valueOf(contents)));
    }

    /**
     * Content-Length framed JSON-RPC channel over stdio, the framing #19
     * implements for LSP.
     */
    private static final class Transport {
        private static final byte[] HEAD = "Content-Length: ".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] SEP = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

        private final InputStream in;
        private final OutputStream out;

        Transport(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }

        Map<String, Object> request(int id, String method, String params) throws IOException {
            String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\""
                    + (params == null ? "" : ",\"params\":" + params) + "}";
            send(body);
            return awaitResponse(id);
        }

        void notify(String method, String params) throws IOException {
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\""
                    + (params == null ? "" : ",\"params\":" + params) + "}";
            send(body);
        }

        private void send(String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            out.write(HEAD);
            out.write(String.valueOf(bytes.length).getBytes(StandardCharsets.US_ASCII));
            out.write(SEP);
            out.write(bytes);
            out.flush();
        }

        private Map<String, Object> awaitResponse(int id) throws IOException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S);
            while (System.nanoTime() < deadline) {
                Map<String, Object> message = JsonExpect.object(
                        Json.parse(new String(readBody(), StandardCharsets.UTF_8)));
                boolean isResponse = !message.containsKey("method") && message.get("id") != null;
                if (isResponse && Integer.valueOf(id).equals(asNumber(message.get("id")))) {
                    if (message.containsKey("error")) {
                        throw new IllegalStateException("server error: " + message.get("error"));
                    }
                    return message;
                }
            }
            throw new IllegalStateException("timed out waiting for response " + id);
        }

        private int readHeaders() throws IOException {
            int contentLength = -1;
            while (true) {
                String line = readHeaderLine();
                if (line.length() == 1 && line.charAt(0) == '\r') {
                    return contentLength;
                }
                if (line.length() > 15 && line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                    contentLength = Integer.parseInt(line.substring(15, line.length() - 1).trim());
                }
                if (line.length() > 8192) {
                    throw new IOException("header line too long");
                }
            }
        }

        private String readHeaderLine() throws IOException {
            StringBuilder line = new StringBuilder();
            while (true) {
                int b = in.read();
                if (b < 0) {
                    throw new IOException("server stream closed during headers");
                }
                if (b == '\n') {
                    return line.toString();
                }
                line.append((char) b);
            }
        }

        private byte[] readBody() throws IOException {
            int contentLength = readHeaders();
            if (contentLength < 0) {
                throw new IOException("missing Content-Length header");
            }
            byte[] body = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int chunk = in.read(body, read, contentLength - read);
                if (chunk < 0) {
                    throw new IOException("server stream closed during body");
                }
                read += chunk;
            }
            return body;
        }

        private static int asNumber(Object value) {
            if (value instanceof Number n) {
                return n.intValue();
            }
            throw new IllegalStateException("expected number, got " + value);
        }
    }

    private static final class JsonExpect {
        @SuppressWarnings("unchecked")
        static Map<String, Object> object(Object value) {
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            throw new IllegalStateException("expected object, got " + value);
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> map(Object value) {
            return (Map<String, Object>) value;
        }
    }

    /**
     * Small JSON tree parser, no external libraries: objects become
     * LinkedHashMap, arrays become ArrayList, numbers become Double.
     */
    private static final class Json {
        static Object parse(String text) {
            Parser parser = new Parser(text);
            Object value = parser.value();
            parser.expectEnd();
            return value;
        }

        private static final class Parser {
            private final String text;
            private int at;

            Parser(String text) {
                this.text = text;
            }

            void expectEnd() {
                skipWhitespace();
                if (at != text.length()) {
                    throw error("trailing characters");
                }
            }

            Object value() {
                skipWhitespace();
                char c = current();
                return switch (c) {
                    case '{' -> object();
                    case '[' -> array();
                    case '"' -> string();
                    case 't' -> literal("true", Boolean.TRUE);
                    case 'f' -> literal("false", Boolean.FALSE);
                    case 'n' -> literal("null", null);
                    default -> number();
                };
            }

            private Map<String, Object> object() {
                Map<String, Object> result = new LinkedHashMap<>();
                at++;
                skipWhitespace();
                if (current() == '}') {
                    at++;
                    return result;
                }
                while (true) {
                    skipWhitespace();
                    String key = string();
                    skipWhitespace();
                    expect(':');
                    at++;
                    result.put(key, value());
                    skipWhitespace();
                    if (current() == ',') {
                        at++;
                        continue;
                    }
                    expect('}');
                    at++;
                    return result;
                }
            }

            private List<Object> array() {
                List<Object> result = new ArrayList<>();
                at++;
                skipWhitespace();
                if (current() == ']') {
                    at++;
                    return result;
                }
                while (true) {
                    result.add(value());
                    skipWhitespace();
                    if (current() == ',') {
                        at++;
                        continue;
                    }
                    expect(']');
                    at++;
                    return result;
                }
            }

            private String string() {
                expect('"');
                at++;
                StringBuilder chars = new StringBuilder();
                while (true) {
                    char c = current();
                    at++;
                    if (c == '"') {
                        return chars.toString();
                    }
                    if (c == '\\') {
                        chars.append(escape());
                    } else {
                        chars.append(c);
                    }
                }
            }

            private char escape() {
                char c = current();
                at++;
                return switch (c) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'u' -> unicode();
                    default -> throw error("unsupported escape \\" + c);
                };
            }

            private char unicode() {
                if (at + 4 > text.length()) {
                    throw error("truncated unicode escape");
                }
                char decoded = (char) Integer.parseInt(text.substring(at, at + 4), 16);
                at += 4;
                return decoded;
            }

            private Object number() {
                int start = at;
                while (at < text.length() && "-+.eE0123456789".indexOf(text.charAt(at)) >= 0) {
                    at++;
                }
                return Double.parseDouble(text.substring(start, at));
            }

            private Object literal(String token, Object value) {
                if (!text.startsWith(token, at)) {
                    throw error("invalid token");
                }
                at += token.length();
                return value;
            }

            private void expect(char c) {
                if (current() != c) {
                    throw error("expected " + c + ", got " + current());
                }
            }

            private char current() {
                if (at >= text.length()) {
                    throw error("unexpected end of input");
                }
                return text.charAt(at);
            }

            private void skipWhitespace() {
                while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) {
                    at++;
                }
            }

            private IllegalArgumentException error(String message) {
                return new IllegalArgumentException("json at " + at + ": " + message);
            }
        }
    }
}
