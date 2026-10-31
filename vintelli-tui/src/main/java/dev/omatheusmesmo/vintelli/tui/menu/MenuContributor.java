package dev.omatheusmesmo.vintelli.tui.menu;

import java.util.List;

/** CDI providers contribute menu entries without changing the shell. */
public interface MenuContributor {
    List<MenuEntry> menuEntries();
}
