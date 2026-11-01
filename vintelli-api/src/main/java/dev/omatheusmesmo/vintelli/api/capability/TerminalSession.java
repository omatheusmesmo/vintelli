package dev.omatheusmesmo.vintelli.api.capability;

import java.util.List;

/**
 * One long-lived interactive shell session. The session owns its child
 * process and must be closed when its terminal window ends.
 */
public interface TerminalSession extends AutoCloseable {

    /** Sends input bytes to the child terminal. */
    void write(byte[] input);

    /**
     * Returns the most recent bounded output lines. Implementations may strip
     * terminal control sequences when the presentation surface is not a full
     * terminal emulator.
     */
    List<String> output();

    /** Updates the terminal dimensions when the backend supports resizing. */
    void resize(int columns, int rows);

    /** @return whether the shell process is still alive */
    boolean isAlive();

    @Override
    void close();
}
