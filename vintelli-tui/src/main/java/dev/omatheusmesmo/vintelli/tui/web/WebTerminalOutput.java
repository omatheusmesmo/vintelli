package dev.omatheusmesmo.vintelli.tui.web;

/**
 * Byte sink of a web terminal session: the backend hands every ANSI update
 * (cursor moves, style changes, cell text) to this hook in draw order and the
 * surface behind it transports the payload to the client. Decouples the
 * TamboUI side of the session from the WebSocket layer, which injects an
 * implementation that writes WebSocket binary frames.
 */
public interface WebTerminalOutput {

    /**
     * Sends one ANSI update to the client. Implementations block until the
     * payload leaves the process (or fail fast when the connection is gone);
     * the backend serializes writes, so one call never interleaves with
     * another for the same session.
     */
    void write(String ansi);
}
