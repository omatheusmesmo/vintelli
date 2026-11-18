package dev.omatheusmesmo.vintelli.tui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyCallSiteGuardTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final String STRICT_KEY_CHECK = ".isKey(KeyCode.";
    private static final String HELPER_FILE = "Keys.java";

    @Test
    void noTuiSourceChecksAKeyCodeStrictlyOutsideTheKeysHelper() throws IOException {
        assertTrue(Files.isDirectory(MAIN_SOURCES), "the guard reads the TUI sources at " + MAIN_SOURCES.toAbsolutePath());

        List<String> offenders;
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            offenders = files
                    .filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !file.getFileName().toString().equals(HELPER_FILE))
                    .filter(file -> readSource(file).contains(STRICT_KEY_CHECK))
                    .map(file -> MAIN_SOURCES.relativize(file).toString())
                    .sorted()
                    .toList();
        }

        assertTrue(offenders.isEmpty(),
                "use Keys.is(event, KeyCode.X) instead of event.isKey(KeyCode.X): the keypad twin of X would not match. Offenders: "
                        + offenders);
    }

    private static String readSource(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read " + file, failure);
        }
    }
}
