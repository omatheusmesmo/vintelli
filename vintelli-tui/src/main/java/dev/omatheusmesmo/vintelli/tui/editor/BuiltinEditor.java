package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.ShellEditorPalette;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityFailure;
import dev.omatheusmesmo.vintelli.api.capability.CapabilityResult;
import dev.omatheusmesmo.vintelli.api.capability.DiagnosticsCapability.Diagnostic;
import dev.omatheusmesmo.vintelli.api.capability.EditorBridge;
import dev.omatheusmesmo.vintelli.api.capability.ProviderHealth;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.omatheusmesmo.vintelli.tui.ToolWindow;
import dev.omatheusmesmo.vintelli.tui.keymap.ConfigDirectory;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Built-in adapter. All document, element and external inspection access shares this monitor. */
@Singleton
public final class BuiltinEditor implements EditorBridge, ToolWindow {
    public enum CloseResult { CLOSED, CONFIRM_REQUIRED, EMPTY }
    public record TabView(String file, boolean dirty, boolean active) {}
    public record DocumentView(String file, String text, int line, int column, boolean dirty) {}
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    private final List<EditorDocument> documents = new ArrayList<>();
    private final Supplier<ShellEditorPalette> palette;
    private final EditorElement element;
    private Path project;
    private EditorSessionStore sessionStore;
    private int active = -1;
    private Path pendingClose;
    private String status = "Open a file from Project or Go to File";
    private boolean sessionLoaded;
    private String focusRequest;

    @Inject
    public BuiltinEditor(@ConfigProperty(name = "vintelli.local.project-root") Optional<String> configuredRoot,
            Instance<ShellThemes> themes) {
        this(configuredRoot.filter(value -> !value.isBlank()).map(Path::of).orElse(Path.of("")), null,
                () -> themes.get().editorPalette());
        Path config = ConfigDirectory.configRoot(ConfigDirectory.sharedConfigDir(), System.getenv("XDG_CONFIG_HOME"),
                System.getenv("APPDATA"), System.getProperty("user.home"));
        if (config != null) {
            sessionStore = new EditorSessionStore(config.resolve("vintelli/editor-tabs").resolve(projectKey(project) + ".properties"));
        }
    }

    /** Explicit root and private session path for tests and embedding; null disables persistence. */
    public BuiltinEditor(Path project, Path sessionFile, Supplier<ShellEditorPalette> palette) {
        Path absoluteProject = project.toAbsolutePath().normalize();
        try {
            this.project = absoluteProject.toRealPath();
        } catch (IOException unavailable) {
            this.project = absoluteProject;
        }
        this.palette = palette;
        sessionStore = sessionFile == null ? null : new EditorSessionStore(sessionFile);
        element = new EditorElement(this);
    }

    @Override public String identifier() { return "builtin"; }
    @Override public ProviderHealth health() { return ProviderHealth.available(); }
    @Override public String provenance() { return "built-in TamboUI TextArea editor"; }
    @Override public String id() { return "editor"; }
    @Override public String title() { return "Editor"; }
    @Override public char shortcut() { return '5'; }
    @Override public int ordinal() { return 40; }
    @Override public boolean capturesInput() { return true; }
    @Override public boolean handleKey(KeyEvent key) { return element.handleKeyEvent(key, true).isHandled(); }
    @Override public synchronized void render(Frame frame, Rect area) {
        element.render(frame, area, DefaultRenderContext.createEmpty());
    }

    public Element element() { return element; }
    public synchronized Path project() { return project; }
    public synchronized String status() { return status; }
    synchronized ShellEditorPalette palette() { return palette.get(); }
    synchronized String consumeFocusRequest() {
        String requested = focusRequest;
        focusRequest = null;
        return requested;
    }
    public synchronized void requestFocus() { focusRequest = "editor-text-area"; }
    synchronized EditorDocument document() { return active < 0 ? null : documents.get(active); }

    @Override
    public synchronized CapabilityResult<Opened> open(OpenRequest request) {
        ensureSession();
        return openFile(request, true);
    }

    private CapabilityResult<Opened> openFile(OpenRequest request, boolean persist) {
        try {
            Path root = project.toRealPath();
            Path file = (request.file().isAbsolute() ? request.file() : root.resolve(request.file())).toRealPath();
            if (!file.startsWith(root)) { return failed("The file is outside the project", file.toString()); }
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) {
                return failed("Only regular text files up to 4 MiB can be opened", file.toString());
            }
            int existing = -1;
            for (int i = 0; i < documents.size(); i++) { if (documents.get(i).file().equals(file)) { existing = i; break; } }
            if (existing < 0) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.indexOf('\0') >= 0) { return failed("Binary files cannot be edited", file.toString()); }
                if (hasMixedLineEndings(text)) {
                    return failed("Files with mixed line endings cannot be edited safely", file.toString());
                }
                documents.add(new EditorDocument(file, text));
                existing = documents.size() - 1;
            }
            active = existing;
            focusRequest = "editor-text-area";
            EditorDocument document = document();
            document.position(request.line(), 0);
            status = "Opened " + project.relativize(file);
            if (persist) { persist(); }
            return CapabilityResult.ok(new Opened(file, document.row()));
        } catch (IOException | RuntimeException failure) {
            return failed("Cannot open file", failure.getMessage());
        }
    }

    @Override public synchronized Optional<Path> currentFile() {
        ensureSession();
        return Optional.ofNullable(document()).map(EditorDocument::file);
    }

    public synchronized Optional<DocumentView> activeDocument() {
        ensureSession();
        EditorDocument document = document();
        return document == null ? Optional.empty() : Optional.of(new DocumentView(document.file().toString(), document.text(),
                document.row(), document.column(), document.dirty()));
    }

    public synchronized List<TabView> tabs() {
        ensureSession();
        List<TabView> result = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            EditorDocument document = documents.get(i);
            result.add(new TabView(project.relativize(document.file()).toString(), document.dirty(), i == active));
        }
        return List.copyOf(result);
    }

    public synchronized CapabilityResult<Boolean> save() {
        if (document() == null) { return failed("No file is open", ""); }
        try {
            Path file = document().file();
            Path root = project.toRealPath();
            if (!file.normalize().startsWith(root) || !file.getParent().toRealPath().startsWith(root)
                    || (Files.exists(file) && !file.toRealPath().startsWith(root))) {
                return failed("The file is outside the project", file.toString());
            }
            document().save();
            status = "Saved " + project.relativize(file);
            persist();
            return CapabilityResult.ok(true);
        } catch (IOException failure) { return failed("Cannot save file", failure.getMessage()); }
    }

    public synchronized boolean undo() { boolean changed = document() != null && document().undo(); if (changed) { persist(); } return changed; }
    public synchronized boolean redo() { boolean changed = document() != null && document().redo(); if (changed) { persist(); } return changed; }
    public synchronized boolean findNext(String query) {
        boolean found = document() != null && document().find(query);
        status = found ? "Found: " + query : "No match: " + query;
        return found;
    }
    public synchronized void insert(String text) {
        if (document() == null) { return; }
        document().mutate(() -> document().state().insert(text.replace("\r\n", "\n")));
        persist();
    }
    public synchronized void nextTab() { selectTab(active + 1); }
    public synchronized void previousTab() { selectTab(active - 1); }
    public synchronized void selectTab(int index) {
        if (documents.isEmpty()) { return; }
        active = Math.floorMod(index, documents.size());
        persist();
    }

    public synchronized CloseResult closeActive() {
        if (document() == null) { return CloseResult.EMPTY; }
        if (document().dirty()) {
            pendingClose = document().file();
            focusRequest = "editor-close-confirmation";
            status = "Discard unsaved changes to " + project.relativize(pendingClose) + "? [y/N]";
            return CloseResult.CONFIRM_REQUIRED;
        }
        removeActive();
        return CloseResult.CLOSED;
    }

    public synchronized boolean closeConfirmationPending() { return pendingClose != null; }
    public synchronized void confirmClose(boolean discard) {
        Path closing = pendingClose;
        pendingClose = null;
        if (!discard || closing == null) {
            status = "Close cancelled";
            focusRequest = document() == null ? null : "editor-text-area";
            return;
        }
        boolean found = false;
        for (int i = 0; i < documents.size(); i++) {
            if (documents.get(i).file().equals(closing)) { active = i; removeActive(); found = true; break; }
        }
        if (!found) {
            status = "Tab already closed";
            focusRequest = document() == null ? null : "editor-text-area";
        }
    }

    private void removeActive() {
        documents.remove(active);
        active = Math.min(active, documents.size() - 1);
        focusRequest = document() == null ? null : "editor-text-area";
        status = "Tab closed";
        persist();
    }

    public synchronized void setDiagnostics(List<Diagnostic> diagnostics) {
        EditorDocument document = document();
        if (document != null) {
            document.diagnostics(diagnostics.stream().filter(value -> {
                Path path = value.filePath().isAbsolute() ? value.filePath() : project.resolve(value.filePath());
                try {
                    return path.toRealPath().equals(document.file());
                } catch (IOException unavailable) {
                    return path.toAbsolutePath().normalize().equals(document.file());
                }
            }).toList());
        }
    }

    public synchronized boolean jumpToDiagnostic(int index) {
        if (document() == null || index < 0 || index >= document().diagnostics().size()) { return false; }
        Diagnostic diagnostic = document().diagnostics().get(index);
        document().position(diagnostic.startLine(), diagnostic.startColumn());
        status = diagnostic.severity() + ": " + diagnostic.message();
        return true;
    }

    public synchronized List<Diagnostic> diagnostics() { return document() == null ? List.of() : document().diagnostics(); }

    synchronized void changed() { persist(); }

    public synchronized void restoreSession() { ensureSession(); }
    private void ensureSession() {
        if (sessionLoaded) { return; }
        sessionLoaded = true;
        if (sessionStore == null) { return; }
        try {
            EditorSessionStore.Session session = sessionStore.load(project);
            for (EditorSessionStore.Tab tab : session.tabs()) {
                if (openFile(new OpenRequest(tab.file(), tab.row()), false).isFailure()) {
                    if (tab.recovery() == null) { continue; }
                    try {
                        Path root = project.toRealPath();
                        Path candidate = tab.file().isAbsolute() ? tab.file() : root.resolve(tab.file());
                        Path recoveryFile = candidate.normalize();
                        if (!recoveryFile.startsWith(root)) { continue; }
                        try {
                            if (Files.exists(recoveryFile) && !recoveryFile.toRealPath().startsWith(root)) { continue; }
                        } catch (IOException io) { continue; }
                        documents.add(new EditorDocument(recoveryFile, tab.recovery(), null));
                        active = documents.size() - 1;
                        status = "Recovered unsaved text for unavailable file " + project.relativize(recoveryFile);
                    } catch (IOException | RuntimeException ignored) { continue; }
                }
                if (tab.recovery() != null) { document().state().setText(tab.recovery()); }
                document().position(tab.row(), tab.column());
            }
            if (!documents.isEmpty()) { active = Math.clamp(session.selected(), 0, documents.size() - 1); }
        } catch (IOException | RuntimeException failure) { status = "Cannot restore editor session: " + failure.getMessage(); }
    }

    @PreDestroy
    public synchronized void persist() {
        if (sessionStore == null || !sessionLoaded) { return; }
        try { sessionStore.save(project, documents, active); }
        catch (IOException failure) { status = "Cannot persist editor session: " + failure.getMessage(); }
    }

    private <T> CapabilityResult<T> failed(String reason, String detail) {
        status = reason + (detail == null || detail.isEmpty() ? "" : ": " + detail);
        return CapabilityResult.failed(new CapabilityFailure(reason, detail));
    }

    private static String projectKey(Path path) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static boolean hasMixedLineEndings(String text) {
        boolean crlf = text.contains("\r\n");
        boolean bareLf = text.replace("\r\n", "").contains("\n");
        boolean bareCr = text.replace("\r\n", "").contains("\r");
        return bareCr || (crlf && bareLf);
    }
}
