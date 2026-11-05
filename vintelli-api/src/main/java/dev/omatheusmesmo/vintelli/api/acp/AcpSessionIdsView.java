package dev.omatheusmesmo.vintelli.api.acp;

import java.util.List;

/** Read-only view of the opaque IDs for currently connected ACP sessions. */
public interface AcpSessionIdsView {

    /** Returns only session IDs, without workspace or conversation data. */
    List<String> activeSessionIds();
}
