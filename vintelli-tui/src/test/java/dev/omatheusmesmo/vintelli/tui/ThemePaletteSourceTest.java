package dev.omatheusmesmo.vintelli.tui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.omatheusmesmo.vintelli.api.ShellEditorPalette;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies both shipped themes against verbatim excerpts of the official
 * IntelliJ platform files: the palette pointer files resolve every palette
 * entry through a documented path into the excerpted platform JSON and XML
 * fragments, so a wrong value copied into the stylesheet fails here instead
 * of matching a hand written copy. Every palette entry cites its source file
 * and path, values reached through color references resolve through the
 * `colors` map of the same file, every hex literal in a stylesheet traces
 * back to the excerpted value set, and the editor section stays its own
 * palette.
 */
class ThemePaletteSourceTest {

    private static final String RESOURCE_DIR = "/dev/omatheusmesmo/vintelli/tui/themes/";
    private static final String SOURCE_DIR = RESOURCE_DIR + "source/";
    private static final Pattern HEX = Pattern.compile("#[0-9A-Fa-f]{8}\\b|#[0-9A-Fa-f]{6}\\b");
    private static final List<String> THEMES = List.of("darcula", "dark");

    @Test
    void shippedPalettesMatchTheirPlatformSources() throws IOException {
        for (String theme : THEMES) {
            JsonNode spec = readJson(theme + "-palette.json");
            assertExcerptProvenance(spec);
            ShellThemes themes = new ShellThemes(theme, (Path) null);

            Map<String, String> stylesheet = themes.variablesOf(theme);
            assertEquals(stylesheet.keySet(), resolvedPalette(spec).keySet(),
                    "the stylesheet declares exactly the palette keys sourced for '" + theme + "'");

            Map<String, String> resolved = resolvedPalette(spec);
            for (Map.Entry<String, String> entry : stylesheet.entrySet()) {
                String expected = resolved.get(entry.getKey());
                assertTrue(expected.equalsIgnoreCase(entry.getValue()),
                        "palette key '" + entry.getKey() + "' of '" + theme + "' must equal its source value: "
                                + expected + " vs " + entry.getValue());
            }
            for (String reused : keySetOf(spec, "origin", "chosenFrom")) {
                assertTrue(stylesheet.containsKey(reused), "the reuse note in the origin block names a real palette key");
            }
        }
    }

    @Test
    void stylesheetHexLiteralsAllTraceBackToThePlatformSources() throws IOException {
        for (String theme : THEMES) {
            Set<String> sourced = new HashSet<>();
            for (String file : sourceFilesOf(readJson(theme + "-palette.json"))) {
                assertTrue(readText(SOURCE_DIR + file).contains("IU-262.10968.63"),
                        "the release is recorded in every source file excerpt");
                if (file.endsWith(".xml")) {
                    sourced.addAll(hexValuesFromXml(readXml(file)));
                } else {
                    sourced.addAll(hexLiterals(readText(SOURCE_DIR + file)));
                }
            }
            assertTrue(!sourced.isEmpty(), "the source set must not be empty");
            Set<String> literals = hexLiterals(stylesheetSource(theme));
            assertTrue(sourced.containsAll(literals),
                    "unsourced hex literals in '" + theme + "': " + literals + "; sourced set: " + sourced);
            assertTrue(!literals.isEmpty(), "the stylesheet must not be empty of colors");
        }
    }

    @Test
    void editorPaletteOnlyExposesTheEditorSchemeSection() throws IOException {
        for (String theme : THEMES) {
            Map<String, String> values = resolvedPalette(readJson(theme + "-palette.json"));
            ShellThemes themes = new ShellThemes(theme, (Path) null);

            ShellEditorPalette expected = new ShellEditorPalette(
                    values.get("editor-bg"), values.get("editor-fg"), values.get("editor-keyword"),
                    values.get("editor-string"), values.get("editor-comment"), values.get("editor-number"),
                    values.get("editor-constant"), values.get("editor-selection"), values.get("editor-sel-fg"));
            ShellEditorPalette actual = themes.editorPalette();
            assertTrue(equalsIgnoringCase(expected, actual),
                    "the editor palette of '" + theme + "' is built from the editor scheme values only");
        }
    }

    @Test
    void chromeAndEditorBackgroundsStayDistinctInBothThemes() throws IOException {
        for (String theme : THEMES) {
            Map<String, String> values = resolvedPalette(readJson(theme + "-palette.json"));
            assertTrue(!values.get("panel").equalsIgnoreCase(values.get("editor-bg")),
                    "the chrome background and the editor background must stay distinct in '" + theme + "'");
            assertTrue(!values.get("editor-border").equalsIgnoreCase(values.get("editor-bg")),
                    "the unfocused editor border must stay visible against its own background in '" + theme + "'");
        }
    }

    /**
     * Every platform excerpt the palette pointers resolve through is part of
     * the source set for its theme, so an inherited editor value (the Dark
     * theme selection, taken from the Darcula editor parent scheme) counts
     * as sourced as well.
     */
    private static Set<String> sourceFilesOf(JsonNode spec) {
        Set<String> files = new HashSet<>();
        spec.get("palette").fields().forEachRemaining(entry -> files.add(entry.getValue().get("file").asText()));
        return files;
    }

    private static boolean equalsIgnoringCase(ShellEditorPalette expected, ShellEditorPalette actual) {
        return expected.background().equalsIgnoreCase(actual.background())
                && expected.text().equalsIgnoreCase(actual.text())
                && expected.keyword().equalsIgnoreCase(actual.keyword())
                && expected.string().equalsIgnoreCase(actual.string())
                && expected.comment().equalsIgnoreCase(actual.comment())
                && expected.number().equalsIgnoreCase(actual.number())
                && expected.constant().equalsIgnoreCase(actual.constant())
                && expected.selection().equalsIgnoreCase(actual.selection())
                && expected.selectionText().equalsIgnoreCase(actual.selectionText());
    }

    private static void assertExcerptProvenance(JsonNode spec) {
        JsonNode origin = spec.get("origin");
        assertTrue(origin.get("chrome").asText().contains("theme"), "the chrome source is recorded");
        assertTrue(origin.get("editor").asText().contains("scheme"), "the editor source is recorded");
        assertTrue(origin.get("release").asText().contains("2026.2"), "the release is recorded");
    }

    private static Set<String> keySetOf(JsonNode spec, String... path) {
        JsonNode node = spec;
        for (String segment : path) {
            node = node.get(segment);
        }
        Set<String> keys = new HashSet<>();
        node.fields().forEachRemaining(entry -> keys.add(entry.getKey()));
        return keys;
    }

    private static Map<String, String> resolvedPalette(JsonNode spec) {
        Map<String, String> resolved = new HashMap<>();
        spec.get("palette").fields().forEachRemaining(entry -> resolved.put(entry.getKey(), resolve(entry.getValue())));
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            values.put(entry.getKey(), entry.getValue().toUpperCase(Locale.ROOT));
        }
        return values;
    }

    /**
     * Resolves one palette pointer through the verbatim platform excerpt:
     * {@code file} selects the excerpted platform file and {@code path} walks
     * it in the original structure. JSON paths walk `ui` (and, for the New UI
     * Dark file, `colors`) with dotted keys consumed greedily, and values that
     * are color references resolve through the `colors` map of the same file;
     * XML paths select an `option` node by name and then a nested attribute
     * node.
     */
    private static String resolve(JsonNode pointer) {
        String file = pointer.get("file").asText();
        String path = pointer.get("path").asText();
        return file.endsWith(".json") ? resolveJson(file, path) : resolveXml(file, path);
    }

    private static String resolveJson(String file, String path) {
        JsonNode root = readJson(file);
        String[] segments = path.split("\\.");
        JsonNode current = root;
        int index = 0;
        while (index < segments.length) {
            JsonNode next = null;
            int consumed = 0;
            for (int take = segments.length - index; take > 0; take--) {
                String candidate = String.join(".", Arrays.asList(segments).subList(index, index + take));
                JsonNode child = current.get(candidate);
                if (child != null) {
                    next = child;
                    consumed = take;
                    break;
                }
            }
            if (next == null) {
                throw new IllegalStateException("path '" + path + "' not found in " + file);
            }
            current = next;
            index += consumed;
        }
        String value = current.asText();
        if (value.startsWith("#")) {
            return value;
        }
        JsonNode colors = root.get("colors");
        JsonNode color = colors == null ? null : colors.get(value);
        if (color == null) {
            throw new IllegalStateException("value '" + value + "' of '" + path + "' in " + file
                    + " is neither a hex literal nor a colors reference");
        }
        return color.asText();
    }

    private static String resolveXml(String file, String path) {
        Document document = readXml(file);
        String[] segments = path.split("\\.");
        Element option = optionByName(document, file, segments[0], null);
        for (int i = 1; i < segments.length; i++) {
            option = optionAmong(option, file, segments[i], segments[i - 1], true);
        }
        String value = option.getAttribute("value");
        if (value.isBlank()) {
            throw new IllegalStateException("path '" + path + "' in " + file + " carries no value attribute");
        }
        return "#" + value;
    }

    private static Element optionByName(Node parent, String file, String name, String parentName) {
        if (parent instanceof Document document) {
            return optionAmong(document.getDocumentElement(), file, name, parentName, true);
        }
        return optionAmong(parent, file, name, parentName, false);
    }

    private static Element optionAmong(Node parent, String file, String name, String parentName, boolean deep) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element element)) {
                continue;
            }
            if (element.getAttribute("name").equals(name)) {
                return element;
            }
            if (deep) {
                Element nested = optionAmong(element, file, name, parentName, true);
                if (nested != null) {
                    return nested;
                }
            }
        }
        if (!deep) {
            throw new IllegalStateException("option '" + name + "'"
                    + (parentName == null ? "" : " inside '" + parentName + "'") + " not found in " + file);
        }
        return null;
    }

    private static String stylesheetSource(String theme) throws IOException {
        return readText(RESOURCE_DIR + theme + ".tcss");
    }

    private static Set<String> hexValuesFromXml(Document document) {
        Set<String> found = new HashSet<>();
        NodeList options = document.getElementsByTagName("option");
        for (int i = 0; i < options.getLength(); i++) {
            Element option = (Element) options.item(i);
            String value = option.getAttribute("value");
            if (value.matches("[0-9A-Fa-f]{6}")) {
                found.add(("#" + value).toUpperCase(Locale.ROOT));
            }
        }
        return found;
    }

    private static Set<String> hexLiterals(String text) {
        Set<String> found = new HashSet<>();
        Matcher hex = HEX.matcher(text);
        while (hex.find()) {
            String literal = hex.group().toUpperCase(Locale.ROOT);
            found.add(literal.length() > 7 ? literal.substring(0, 7) : literal);
        }
        return found;
    }

    private static JsonNode readJson(String file) {
        try {
            return new ObjectMapper().readTree(open(SOURCE_DIR + file));
        } catch (IOException failure) {
            throw new IllegalStateException("fixture not readable: " + file, failure);
        }
    }

    private static Document readXml(String file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(open(SOURCE_DIR + file));
        } catch (Exception failure) {
            throw new IllegalStateException("fixture not readable: " + file, failure);
        }
    }

    private static String readText(String resource) {
        try {
            return new String(open(resource).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("fixture not readable: " + resource, failure);
        }
    }

    private static InputStream open(String resource) {
        InputStream stream = ThemePaletteSourceTest.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("fixture not found: " + resource);
        }
        return stream;
    }
}
