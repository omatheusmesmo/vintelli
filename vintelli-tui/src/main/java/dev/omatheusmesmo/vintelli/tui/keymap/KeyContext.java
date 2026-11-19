package dev.omatheusmesmo.vintelli.tui.keymap;

/**
 * Runtime state that decides which action a shared chord reaches. While a debug session is suspended, the
 * stepping chords ({@code F8}, {@code F7}, {@code Shift+F8} and {@code F9}) belong to the debugger and
 * take the chord from any other action that shares it, such as New File on {@code F7}; without a suspended
 * session the stepping actions stay silent and the other actions keep their chords.
 *
 * @param debugSessionSuspended whether a debug session exists and is stopped at a location
 */
public record KeyContext(boolean debugSessionSuspended) {

    /** The context without a suspended debug session. */
    public static final KeyContext NONE = new KeyContext(false);
}
