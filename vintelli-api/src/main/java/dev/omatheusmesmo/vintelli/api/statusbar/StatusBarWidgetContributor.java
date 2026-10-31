package dev.omatheusmesmo.vintelli.api.statusbar;

import java.util.List;

/**
 * A live source of TUI status bar widgets. Implementations must return a fast,
 * non-blocking snapshot because the shell calls this from its render path.
 */
public interface StatusBarWidgetContributor {

    List<StatusBarWidget> statusBarWidgets();
}
