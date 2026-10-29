import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal LSP test double used by the spike 47 native client benchmark.
 *
 * Speaks the Content-Length framing of JSON-RPC 2.0 and answers the exact
 * message flow the two benchmark clients issue: initialize, initialized,
 * textDocument/hover, shutdown, exit. Anything else is answered with a null
 * result (requests) or ignored (notifications).
 *
 * Run with: java echo-lsp-server.java
 */
class EchoLspServer {

    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*([^,}\\s]+)");
    private static final Pattern URI = Pattern.compile("\"uri\"\\s*:\\s*\"([^\"]+)\"");

    public static void main(String[] args) throws IOException {
        DataInputStream in = new DataInputStream(System.in);
        OutputStream out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out));
        while (handleNext(in, out)) {
            out.flush();
        }
    }

    private static boolean handleNext(DataInputStream in, OutputStream out) throws IOException {
        int contentLength = readHeaders(in);
        if (contentLength < 0) {
            return false;
        }
        byte[] body = new byte[contentLength];
        in.readFully(body);
        String message = new String(body, StandardCharsets.UTF_8);
        String method = match(METHOD, message);
        String id = match(ID, message);
        if (id == null) {
            if ("exit".equals(method)) {
                out.flush();
                System.exit(0);
            }
            return true;
        }
        switch (method == null ? "" : method) {
            case "initialize" -> respond(out, id, """
                    {"capabilities":{"textDocumentSync":1,"hoverProvider":true},\
                    "serverInfo":{"name":"echo-lsp-server","version":"1.0.0"}}""");
            case "textDocument/hover" -> {
                String uri = match(URI, message);
                respond(out, id, "{\"contents\":{\"kind\":\"markdown\",\"value\":\"echo hover on "
                        + uri + "\"}}");
            }
            case "shutdown" -> respond(out, id, "null");
            default -> respond(out, id, "null");
        }
        return true;
    }

    private static void respond(OutputStream out, String id, String result) throws IOException {
        String payload = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        out.write(("Content-Length: " + body.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static int readHeaders(DataInputStream in) throws IOException {
        int contentLength = -1;
        StringBuilder line = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) {
                return -1;
            }
            if (b == '\n') {
                if (line.length() == 1 && line.charAt(0) == '\r') {
                    return contentLength;
                }
                if (line.length() > 15 && line.indexOf("Content-Length:") == 0) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
                if (line.length() > 8192) {
                    return -1;
                }
                line.setLength(0);
            } else {
                line.append((char) b);
            }
        }
    }

    private static String match(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
