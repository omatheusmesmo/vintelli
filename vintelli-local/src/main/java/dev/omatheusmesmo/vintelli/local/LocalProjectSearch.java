package dev.omatheusmesmo.vintelli.local;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.ignore.IgnoreNode;

import dev.omatheusmesmo.vintelli.api.search.ProjectSearch;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/** On-demand confined filesystem search using JGit's gitignore matcher. */
@Singleton
public final class LocalProjectSearch implements ProjectSearch {

    public static final int MAX_FILE_RESULTS = 200;
    public static final int MAX_TEXT_RESULTS = 1000;
    private static final long MAX_TEXT_BYTES = 2 * 1024 * 1024;
    private static final Comparator<FileMatch> FILE_ORDER = Comparator.comparingInt(FileMatch::score)
            .reversed().thenComparing(match -> portable(match.file()));
    private static final Comparator<TextMatch> TEXT_ORDER = Comparator
            .comparing((TextMatch match) -> portable(match.file())).thenComparingInt(TextMatch::line)
            .thenComparingInt(TextMatch::column);

    private final LocalProjectRoot projectRoot;
    private final Set<Task> tasks = ConcurrentHashMap.newKeySet();

    @Inject
    public LocalProjectSearch(LocalProjectRoot projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public SearchTask files(String query, Consumer<List<FileMatch>> results) {
        String filter = query.strip().toLowerCase(Locale.ROOT);
        return start(task -> {
            List<FileMatch> matches = new ArrayList<>();
            task.deliver(() -> results.accept(List.of()));
            walk(task, path -> {
                int score = fuzzyScore(portable(path), filter);
                if (score < 0) {
                    return;
                }
                matches.add(new FileMatch(path, score));
                matches.sort(FILE_ORDER);
                if (matches.size() > MAX_FILE_RESULTS) {
                    matches.removeLast();
                }
                List<FileMatch> snapshot = List.copyOf(matches);
                task.deliver(() -> results.accept(snapshot));
            });
        });
    }

    @Override
    public SearchTask text(String query, boolean regex, Consumer<List<TextMatch>> results) {
        return start(task -> {
            Pattern pattern = Pattern.compile(regex ? query : Pattern.quote(query));
            List<TextMatch> matches = new ArrayList<>();
            task.deliver(() -> results.accept(List.of()));
            if (query.isEmpty()) {
                return;
            }
            walk(task, path -> {
                if (matches.size() >= MAX_TEXT_RESULTS || task.stopped()) {
                    return;
                }
                scanText(task, path, pattern, matches);
                if (!matches.isEmpty()) {
                    matches.sort(TEXT_ORDER);
                    List<TextMatch> snapshot = List.copyOf(matches);
                    task.deliver(() -> results.accept(snapshot));
                }
            });
        });
    }

    private void scanText(Task task, Path path, Pattern pattern, List<TextMatch> matches) throws IOException {
        Path inside = projectRoot.confine(path);
        if (inside == null || Files.size(inside) > MAX_TEXT_BYTES) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(inside, StandardCharsets.UTF_8)) {
            String line;
            int number = 0;
            List<TextMatch> fileMatches = new ArrayList<>();
            while (!task.stopped() && (line = reader.readLine()) != null) {
                if (line.indexOf('\0') >= 0) {
                    return;
                }
                Matcher matcher = pattern.matcher(new InterruptibleSequence(line));
                while (!task.stopped() && matches.size() + fileMatches.size() < MAX_TEXT_RESULTS && matcher.find()) {
                    fileMatches.add(new TextMatch(path, number, matcher.start(), line));
                }
                if (matches.size() + fileMatches.size() >= MAX_TEXT_RESULTS) {
                    break;
                }
                number++;
            }
            if (!task.stopped()) {
                matches.addAll(fileMatches);
            }
        } catch (CharacterCodingException binary) {
            // Non UTF-8 files are not text search candidates.
        }
    }

    @Override
    public List<Path> listFiles() {
        List<Path> paths = new ArrayList<>();
        try {
            walk(new Task(), paths::add);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        paths.sort(Comparator.comparing(LocalProjectSearch::portable));
        return List.copyOf(paths);
    }

    @Override
    public List<Path> listDirectories() {
        List<Path> paths = new ArrayList<>();
        try {
            walk(new Task(), ignored -> { }, paths::add);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        paths.sort(Comparator.comparing(LocalProjectSearch::portable));
        return List.copyOf(paths);
    }

    private void walk(Task task, FileConsumer files) throws IOException {
        walk(task, files, ignored -> { });
    }

    private void walk(Task task, FileConsumer files, FileConsumer directories) throws IOException {
        Path root = projectRoot.canonical();
        Map<Path, IgnoreNode> ignores = new HashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (task.stopped()) {
                    return FileVisitResult.TERMINATE;
                }
                if (!directory.equals(root) && (directory.getFileName().toString().equals(".git")
                        || projectRoot.confine(directory) == null || ignored(directory, true))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!directory.equals(root)) {
                    directories.accept(root.relativize(directory));
                }
                Path ignoreFile = directory.resolve(".gitignore");
                Path confined = projectRoot.confine(ignoreFile);
                if (confined != null && Files.isRegularFile(confined)) {
                    IgnoreNode node = new IgnoreNode();
                    try (InputStream input = Files.newInputStream(confined)) {
                        node.parse(input);
                    }
                    ignores.put(directory, node);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (task.stopped()) {
                    return FileVisitResult.TERMINATE;
                }
                Path inside = projectRoot.confine(file);
                if (inside != null && Files.isRegularFile(inside) && !ignored(file, false)
                        && !file.getFileName().toString().equals(".git")) {
                    files.accept(root.relativize(file));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                if (task.stopped()) {
                    return FileVisitResult.TERMINATE;
                }
                throw failure;
            }

            private boolean ignored(Path path, boolean directory) {
                for (Path parent = path.getParent(); parent != null && parent.startsWith(root); parent = parent.getParent()) {
                    IgnoreNode node = ignores.get(parent);
                    if (node != null) {
                        Boolean ignored = node.checkIgnored(portable(parent.relativize(path)), directory);
                        if (ignored != null) {
                            return ignored;
                        }
                    }
                }
                return false;
            }
        });
    }

    /** A subsequence match favors exact names, name prefixes, boundaries and adjacent letters. */
    static int fuzzyScore(String path, String query) {
        if (query.isEmpty()) {
            return 0;
        }
        String candidate = path.toLowerCase(Locale.ROOT);
        String name = candidate.substring(candidate.lastIndexOf('/') + 1);
        int score = name.equals(query) ? 10000 : name.startsWith(query) ? 5000 : name.contains(query) ? 2000 : 0;
        int offset = 0;
        int previous = -2;
        for (int index = 0; index < query.length(); index++) {
            int found = candidate.indexOf(query.charAt(index), offset);
            if (found < 0) {
                return -1;
            }
            score += 10;
            if (found == previous + 1) {
                score += 20;
            }
            if (found == 0 || "/._-".indexOf(candidate.charAt(found - 1)) >= 0
                    || Character.isUpperCase(path.charAt(found))) {
                score += 30;
            }
            previous = found;
            offset = found + 1;
        }
        return Math.max(0, score - candidate.length());
    }

    private Task start(SearchWork work) {
        Task task = new Task();
        tasks.add(task);
        task.worker = Thread.ofVirtual().name("vintelli-project-search").unstarted(() -> {
            try {
                work.run(task);
                task.completion.complete(null);
            } catch (Exception failure) {
                if (task.cancelled()) {
                    task.completion.complete(null);
                } else {
                    task.completion.completeExceptionally(failure);
                }
            } finally {
                tasks.remove(task);
            }
        });
        task.worker.start();
        return task;
    }

    @PreDestroy
    void close() {
        tasks.forEach(Task::cancel);
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    @FunctionalInterface
    private interface FileConsumer {
        void accept(Path file) throws IOException;
    }

    @FunctionalInterface
    private interface SearchWork {
        void run(Task task) throws IOException;
    }

    /** Makes JDK regex backtracking responsive to the worker interruption used by cancellation. */
    private static final class InterruptibleSequence implements CharSequence {
        private final CharSequence value;

        private InterruptibleSequence(CharSequence value) {
            this.value = value;
        }

        @Override
        public int length() {
            return value.length();
        }

        @Override
        public char charAt(int index) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Search cancelled");
            }
            return value.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new InterruptibleSequence(value.subSequence(start, end));
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }

    private static final class Task implements SearchTask {
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private volatile boolean cancelled;
        private volatile Thread worker;

        @Override
        public synchronized void cancel() {
            cancelled = true;
            if (worker != null) {
                worker.interrupt();
            }
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        boolean stopped() {
            return cancelled || Thread.currentThread().isInterrupted();
        }

        synchronized void deliver(Runnable callback) {
            if (!stopped()) {
                callback.run();
            }
        }

        @Override
        public CompletionStage<Void> completion() {
            return completion;
        }
    }
}
