package dev.omatheusmesmo.vintelli.tui.editor;

import dev.omatheusmesmo.vintelli.api.capability.EditorBridge.OpenRequest;
import dev.omatheusmesmo.vintelli.tui.ShellThemes;
import dev.omatheusmesmo.vintelli.tui.icons.FileIcons;
import dev.omatheusmesmo.vintelli.tui.icons.IconMode;
import dev.omatheusmesmo.vintelli.tui.icons.JavaTypeKind;
import dev.omatheusmesmo.vintelli.tui.icons.JavaTypeKinds;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorTabIconsTest {

    private static final Rect TABS = new Rect(0, 0, 80, 2);
    private static final Color INTERFACE = Color.hex("#6CBF6C");

    @TempDir
    Path project;

    @Test
    void aJavaTabShowsItsKindIconOnceTheKindIsKnownAndAnUnknownOneKeepsTheExtensionIcon() throws IOException {
        Files.writeString(project.resolve("Shape.java"), "public interface Shape {}\n");
        Files.writeString(project.resolve("Notes.java"), "package demo;\n");
        for (String face : List.of("nerd", "ascii")) {
            withIconFace(face, () -> {
                BuiltinEditor editor = editor();
                editor.useJavaTypeKinds(new JavaTypeKinds(Files::readString, Runnable::run));
                editor.open(new OpenRequest(project.resolve("Shape.java"), 0));
                editor.open(new OpenRequest(project.resolve("Notes.java"), 0));
                FileIcons icons = FileIcons.resolve();
                String shapeExtension = icons.iconFor("Shape.java").symbol(icons.mode()) + " Shape.java";
                String shapeKind = icons.iconFor("Shape.java", JavaTypeKind.INTERFACE).symbol(icons.mode())
                        + " Shape.java";
                String notes = icons.iconFor("Notes.java").symbol(icons.mode()) + " Notes.java";

                Buffer first = renderTabs(editor);
                assertTrue(rowText(first).contains(shapeExtension), "unknown on the first draw: " + rowText(first));

                Buffer second = renderTabs(editor);
                assertTrue(rowText(second).contains(shapeKind), face + ": " + rowText(second));
                assertFalse(rowText(second).contains(shapeExtension), face + ": " + rowText(second));
                assertTrue(rowText(second).contains(notes), face + ": " + rowText(second));
                assertEquals(rowText(first).indexOf(notes), rowText(second).indexOf(notes),
                        face + ": the kind icon keeps the column of the tabs after it");

                int x = rowText(second).indexOf(shapeKind);
                assertEquals(INTERFACE, second.get(x, 0).style().fg().orElse(null),
                        face + ": the kind icon carries the interface colour");
            });
        }
    }

    private BuiltinEditor editor() {
        ShellThemes themes = new ShellThemes("dark", (Path) null);
        return new BuiltinEditor(project, null, themes::editorPalette);
    }

    private static Buffer renderTabs(BuiltinEditor editor) {
        Buffer buffer = Buffer.empty(TABS);
        DefaultRenderContext context = DefaultRenderContext.createEmpty();
        context.setStyleEngine(new ShellThemes(ShellThemes.DEFAULT_THEME, (Path) null).engine());
        editor.renderTabBar(Frame.forTesting(buffer), TABS, context, false, " ");
        return buffer;
    }

    private static String rowText(Buffer buffer) {
        StringBuilder text = new StringBuilder();
        for (int x = 0; x < buffer.width(); x++) {
            text.append(buffer.get(x, 0).symbol());
        }
        return text.toString();
    }

    private static void withIconFace(String face, Runnable body) {
        String previous = System.getProperty(IconMode.CONFIG_PROPERTY);
        System.setProperty(IconMode.CONFIG_PROPERTY, face);
        try {
            body.run();
        } finally {
            if (previous == null) {
                System.clearProperty(IconMode.CONFIG_PROPERTY);
            } else {
                System.setProperty(IconMode.CONFIG_PROPERTY, previous);
            }
        }
    }
}
