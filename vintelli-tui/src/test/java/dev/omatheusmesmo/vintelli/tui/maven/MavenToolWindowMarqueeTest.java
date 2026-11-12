package dev.omatheusmesmo.vintelli.tui.maven;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;

class MavenToolWindowMarqueeTest {

    private static final String MODULE = "vintelli-marquee-module-with-a-long-name";
    private static final Rect AREA = new Rect(0, 0, 18, 4);
    private static final String SELECTED_START = "› ▾ vintelli-marqu";
    private static final String SELECTED_NEXT = "› ▾ intelli-marque";

    @TempDir
    Path root;

    @Test
    void theSelectedRowScrollsAtEachTickAndTheMarkerStaysInPlace() throws Exception {
        Files.writeString(root.resolve("pom.xml"), pom(MODULE));
        AtomicLong now = new AtomicLong();
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        MavenToolWindow window = new MavenToolWindow(() -> themes, root, now::get);
        window.useMarquee(true);
        window.load();
        awaitLoaded(window);
        window.tickMarquee();

        assertTrue(render(window).startsWith(SELECTED_START), render(window));

        now.set(1_150_000_000L);
        window.tickMarquee();
        String second = render(window);
        assertTrue(second.startsWith(SELECTED_NEXT), second);
        assertTrue(second.contains("  clean"), "the phases that are not selected stay whole: " + second);
    }

    @Test
    void aSelectedRowThatFitsStaysWhole() throws Exception {
        Files.writeString(root.resolve("pom.xml"), pom("short"));
        AtomicLong now = new AtomicLong();
        ShellThemes themes = new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null);
        MavenToolWindow window = new MavenToolWindow(() -> themes, root, now::get);
        window.useMarquee(true);
        window.load();
        awaitLoaded(window);

        now.set(60_000_000_000L);
        window.tickMarquee();

        assertFalse(window.marqueeMoving());
        assertTrue(render(window).startsWith("› ▾ short"), render(window));
    }

    private static void awaitLoaded(MavenToolWindow window) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!render(window).contains("▾") && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static String render(MavenToolWindow window) {
        Buffer buffer = Buffer.empty(AREA);
        window.render(Frame.forTesting(buffer), AREA);
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < buffer.height(); y++) {
            for (int x = 0; x < buffer.width(); x++) {
                text.append(buffer.get(x, y).symbol());
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static String pom(String artifactId) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>dev.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                </project>
                """.formatted(artifactId);
    }
}
