package dev.omatheusmesmo.vintelli.tui.editor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import dev.omatheusmesmo.vintelli.api.capability.DebugCapability.ExceptionBreakpoints;

/**
 * Project scoped choice of which exceptions suspend the debuggee, kept beside the source breakpoints
 * so a new debug session starts with it. A file written for another project is ignored.
 */
final class ExceptionBreakpointStore {

    private final Path file;

    ExceptionBreakpointStore(Path file) {
        this.file = file;
    }

    Optional<ExceptionBreakpoints> load(Path project) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        if (!project.toRealPath().toString().equals(properties.getProperty("project"))) {
            return Optional.empty();
        }
        return Optional.of(new ExceptionBreakpoints(Boolean.parseBoolean(properties.getProperty("uncaught")),
                Boolean.parseBoolean(properties.getProperty("caught"))));
    }

    void save(Path project, ExceptionBreakpoints breakpoints) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("project", project.toRealPath().toString());
        properties.setProperty("uncaught", Boolean.toString(breakpoints.uncaught()));
        properties.setProperty("caught", Boolean.toString(breakpoints.caught()));
        BreakpointStore.writeAtomically(file, properties, "Vintelli exception breakpoints");
    }
}
