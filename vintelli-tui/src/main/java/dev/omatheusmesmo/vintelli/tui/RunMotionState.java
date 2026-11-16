package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.RunCapability.RunCompletion;

/** The visible lifecycle state of one run. Motion is layered on these states. */
enum RunMotionState {
    IDLE("▶", "Idle", "run-muted", false),
    STARTING("⠋", "Starting", "run-accent", true),
    RUNNING("⠋", "Running", "run-accent", true),
    STOPPING("-", "Stopping", "run-warning", true),
    SUCCEEDED("✓", "Succeeded", "run-success", false),
    FAILED("✗", "Failed", "run-error", false),
    STOPPED("■", "Stopped", "run-muted", false);

    private final String glyph;
    private final String label;
    private final String themeRole;
    private final boolean active;

    RunMotionState(String glyph, String label, String themeRole, boolean active) {
        this.glyph = glyph;
        this.label = label;
        this.themeRole = themeRole;
        this.active = active;
    }

    String glyph() { return glyph; }

    String label() { return label; }

    String themeRole() { return themeRole; }

    boolean active() { return active; }

    static RunMotionState completed(RunCompletion completion) {
        return switch (completion.outcome()) {
            case CANCELLED -> STOPPED;
            case TIMED_OUT -> FAILED;
            case EXITED -> completion.exitCode().orElse(-1) == 0 ? SUCCEEDED : FAILED;
        };
    }
}
