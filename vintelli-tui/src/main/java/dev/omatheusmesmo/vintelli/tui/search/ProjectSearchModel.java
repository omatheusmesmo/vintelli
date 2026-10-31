package dev.omatheusmesmo.vintelli.tui.search;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import dev.omatheusmesmo.vintelli.api.search.ProjectSearch;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch.FileMatch;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch.SearchTask;
import dev.omatheusmesmo.vintelli.api.search.ProjectSearch.TextMatch;

/** Search state shared by the Go to File and Find in Files overlays. */
public final class ProjectSearchModel {

    public enum Mode {
        FILES,
        TEXT
    }

    private final ProjectSearch search;
    private final AtomicLong generation = new AtomicLong();
    private Mode mode = Mode.FILES;
    private String query = "";
    private boolean regex;
    private int selected;
    private List<FileMatch> files = List.of();
    private List<TextMatch> text = List.of();
    private SearchTask task;
    private boolean open;
    private boolean searching;
    private String error;

    public ProjectSearchModel(ProjectSearch search) {
        this.search = Objects.requireNonNull(search, "search");
    }

    public synchronized void open(Mode mode) {
        cancelCurrent();
        this.mode = Objects.requireNonNull(mode, "mode");
        query = "";
        regex = false;
        selected = 0;
        files = List.of();
        text = List.of();
        error = null;
        searching = false;
        open = true;
        search();
    }

    public synchronized void close() {
        open = false;
        cancelCurrent();
    }

    public synchronized boolean isOpen() { return open; }
    public synchronized Mode mode() { return mode; }
    public synchronized String query() { return query; }
    public synchronized boolean regex() { return regex; }
    public synchronized int selected() { return selected; }
    public synchronized List<FileMatch> files() { return files; }
    public synchronized List<TextMatch> text() { return text; }
    public synchronized boolean searching() { return searching; }
    public synchronized String error() { return error; }

    public synchronized int size() {
        return mode == Mode.FILES ? files.size() : text.size();
    }

    public synchronized void type(int codePoint) {
        if (Character.isValidCodePoint(codePoint) && !Character.isISOControl(codePoint)) {
            query += Character.toString(codePoint);
            refresh();
        }
    }

    public synchronized void backspace() {
        if (!query.isEmpty()) {
            int end = query.offsetByCodePoints(query.length(), -1);
            query = query.substring(0, end);
            refresh();
        }
    }

    public synchronized void toggleRegex() {
        if (mode == Mode.TEXT) {
            regex = !regex;
            refresh();
        }
    }

    public synchronized void move(int delta) {
        selected = Math.clamp(selected + delta, 0, Math.max(0, size() - 1));
    }

    public synchronized void select(int index) {
        selected = Math.clamp(index, 0, Math.max(0, size() - 1));
    }

    public synchronized FileMatch selectedFile() {
        return mode == Mode.FILES && selected < files.size() ? files.get(selected) : null;
    }

    public synchronized TextMatch selectedText() {
        return mode == Mode.TEXT && selected < text.size() ? text.get(selected) : null;
    }

    private void refresh() {
        selected = 0;
        search();
    }

    private void search() {
        cancelCurrent();
        long request = generation.incrementAndGet();
        files = List.of();
        text = List.of();
        error = null;
        searching = open;
        if (!open) {
            return;
        }
        String requestedQuery = query;
        if (mode == Mode.FILES) {
            task = search.files(requestedQuery, snapshot -> {
                synchronized (ProjectSearchModel.this) {
                    if (open && generation.get() == request) {
                        files = snapshot;
                        selected = Math.clamp(selected, 0, Math.max(0, files.size() - 1));
                    }
                }
            });
        } else {
            boolean requestedRegex = regex;
            task = search.text(requestedQuery, requestedRegex, snapshot -> {
                synchronized (ProjectSearchModel.this) {
                    if (open && generation.get() == request) {
                        text = snapshot;
                        selected = Math.clamp(selected, 0, Math.max(0, text.size() - 1));
                    }
                }
            });
        }
        SearchTask current = task;
        current.completion().whenComplete((ignored, failure) -> {
            synchronized (ProjectSearchModel.this) {
                if (open && generation.get() == request && task == current) {
                    searching = false;
                    if (failure != null) {
                        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                        String message = cause.getMessage();
                        error = message == null
                                ? cause.getClass().getSimpleName()
                                : message.replace("\r\n", "\n").replace('\r', '\n');
                    }
                }
            }
        });
    }

    private void cancelCurrent() {
        generation.incrementAndGet();
        if (task != null) {
            SearchTask cancelled = task;
            task = null;
            // Search callbacks acquire this model's monitor. Cancel outside it so a callback holding
            // the task monitor cannot deadlock against this monitor while cancel waits on the task.
            Thread.ofVirtual().start(cancelled::cancel);
        }
        searching = false;
    }
}
