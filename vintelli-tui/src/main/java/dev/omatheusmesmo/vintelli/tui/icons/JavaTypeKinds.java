package dev.omatheusmesmo.vintelli.tui.icons;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The cached {@link JavaTypeKind} of Java source files, read off the calling
 * thread. {@link #kindOf(Path)} never touches the file system: an unknown file
 * answers null at once and is scanned on the executor, and the version
 * advances when a scan changes an answer, so the caller that draws the tree
 * rebuilds its rows on the next frame.
 *
 * <p>Each file is read at most once per modification time. A revalidation
 * checks the modification time and reads again only when it changed. Only the
 * first 8192 bytes are read, which covers the package, the
 * imports and the declaration of ordinary sources; a file that cannot be read
 * or has no type is cached as unknown.
 */
public final class JavaTypeKinds {

    static final int HEAD_BYTES = 8192;

    private static final String JAVA_SUFFIX = ".java";

    @FunctionalInterface
    public interface Reader {

        String head(Path file) throws IOException;
    }

    private record Entry(FileTime modified, JavaTypeKind kind) {
    }

    private final Reader reader;
    private final Executor executor;
    private final Map<Path, Entry> entries = new ConcurrentHashMap<>();
    private final Set<Path> pending = ConcurrentHashMap.newKeySet();
    private final AtomicLong version = new AtomicLong();

    public JavaTypeKinds(Reader reader, Executor executor) {
        this.reader = reader;
        this.executor = executor;
    }

    public static JavaTypeKinds ofFiles() {
        return new JavaTypeKinds(JavaTypeKinds::readHead, task -> Thread.ofVirtual().start(task));
    }

    public static boolean isJavaSource(Path file) {
        Path name = file.getFileName();
        return name != null && isJavaSource(name.toString());
    }

    public static boolean isJavaSource(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(JAVA_SUFFIX) && lower.length() > JAVA_SUFFIX.length();
    }

    public JavaTypeKind kindOf(Path file) {
        if (!isJavaSource(file)) {
            return null;
        }
        Entry entry = entries.get(file);
        if (entry == null) {
            schedule(file);
            return null;
        }
        return entry.kind();
    }

    public void revalidate(Path file) {
        if (isJavaSource(file)) {
            schedule(file);
        }
    }

    public long version() {
        return version.get();
    }

    private void schedule(Path file) {
        if (pending.add(file)) {
            executor.execute(() -> {
                try {
                    publish(file, scanned(file));
                } finally {
                    pending.remove(file);
                }
            });
        }
    }

    private Entry scanned(Path file) {
        FileTime modified;
        try {
            if (!Files.isRegularFile(file)) {
                return new Entry(null, null);
            }
            modified = Files.getLastModifiedTime(file);
        } catch (IOException | RuntimeException unreadable) {
            return new Entry(null, null);
        }
        Entry cached = entries.get(file);
        if (cached != null && modified.equals(cached.modified())) {
            return cached;
        }
        try {
            return new Entry(modified, JavaTypeScanner.scan(reader.head(file)).orElse(null));
        } catch (IOException | RuntimeException unreadable) {
            return new Entry(modified, null);
        }
    }

    private void publish(Path file, Entry scanned) {
        Entry previous = entries.put(file, scanned);
        if (previous == null || previous.kind() != scanned.kind()) {
            version.incrementAndGet();
        }
    }

    static String readHead(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return new String(in.readNBytes(HEAD_BYTES), StandardCharsets.UTF_8);
        }
    }
}
