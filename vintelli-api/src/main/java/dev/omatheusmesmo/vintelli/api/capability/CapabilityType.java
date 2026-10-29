package dev.omatheusmesmo.vintelli.api.capability;

/**
 * Capabilities the shell offers through tool windows and CLI commands. Every
 * capability resolves to exactly one provider at a time: the highest ranked
 * healthy one.
 */
public enum CapabilityType {

    RUN("run"),
    TERMINAL("terminal"),
    READ_FILE("read-file"),
    DIAGNOSTICS("diagnostics"),
    CALL_HIERARCHY("call-hierarchy"),
    EDITOR_BRIDGE("editor-bridge");

    private final String label;

    CapabilityType(String label) {
        this.label = label;
    }

    /**
     * Lowercase label used in configuration keys, log lines and status
     * messages.
     */
    public String label() {
        return label;
    }
}
