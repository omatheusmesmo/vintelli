package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.api.capability.VersionControlCapability;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one-letter VCS state marker drawn in the project tree and the editor tab
 * title. {@link #LETTERS} is the set of every letter drawn, so an ASCII file
 * icon outside it cannot read as a Git state.
 */
public final class VcsMarkers {

    public static final Set<String> LETTERS = Arrays.stream(VersionControlCapability.State.values())
            .map(VcsMarkers::of)
            .collect(Collectors.toUnmodifiableSet());

    private VcsMarkers() {
    }

    public static String of(VersionControlCapability.State state) {
        return switch (state) {
            case ADDED -> "A";
            case DELETED -> "D";
            case MODIFIED -> "M";
            case UNTRACKED -> "?";
            case UNMERGED -> "!";
        };
    }
}
