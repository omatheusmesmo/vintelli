package dev.omatheusmesmo.vintelli.tui.maven;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reads the aggregator tree of a Maven project from the {@code pom.xml} files
 * with the JDK DOM parser, so no build or dependency resolution happens. A
 * DOCTYPE is refused, module directories must stay inside the project root,
 * and the number of modules, the depth and the pom size are bounded.
 */
public final class MavenProjectReader {

    static final long MAX_POM_BYTES = 2L * 1024 * 1024;
    static final int MAX_DEPTH = 16;
    static final int MAX_MODULES = 500;

    private MavenProjectReader() {
    }

    /** Reads the project at a directory given as text, resolved to an absolute normalised path. */
    public static MavenProjectLoad read(String projectRoot) {
        return read(Path.of(projectRoot).toAbsolutePath().normalize());
    }

    public static MavenProjectLoad read(Path projectRoot) {
        Path root;
        try {
            root = projectRoot.toRealPath();
        } catch (IOException unavailable) {
            return new MavenProjectLoad.Missing("the project root " + projectRoot + " cannot be read");
        }
        if (!Files.isRegularFile(root.resolve("pom.xml"))) {
            return new MavenProjectLoad.Missing("no pom.xml in the project root");
        }
        try {
            return new MavenProjectLoad.Loaded(readModule(root, root, 0, new HashSet<>(), new int[1]));
        } catch (ProjectReadException failure) {
            return new MavenProjectLoad.Unreadable(failure.getMessage());
        }
    }

    private static MavenModule readModule(Path root, Path directory, int depth, Set<Path> visited, int[] counted) {
        if (depth > MAX_DEPTH) {
            throw new ProjectReadException("modules are nested deeper than " + MAX_DEPTH + " levels");
        }
        Path pom = directory.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new ProjectReadException("no pom.xml in module " + directory.getFileName());
        }
        Path real = realDirectory(directory);
        if (!real.startsWith(root)) {
            throw new ProjectReadException("module " + directory.getFileName() + " is outside the project root");
        }
        if (!visited.add(real)) {
            throw new ProjectReadException("module " + directory.getFileName() + " is listed twice");
        }
        Document document = parse(pom);
        Element project = document.getDocumentElement();
        if (!"project".equals(project.getTagName())) {
            throw new ProjectReadException(pom.getFileName() + " is not a Maven project descriptor");
        }
        String artifactId = childText(project, "artifactId")
                .orElse(String.valueOf(real.getFileName()));
        List<String> profiles = profileIds(project);
        List<MavenModule> children = new ArrayList<>();
        for (String name : moduleNames(project)) {
            if (++counted[0] > MAX_MODULES) {
                throw new ProjectReadException("more than " + MAX_MODULES + " modules");
            }
            children.add(readModule(root, directory.resolve(name).normalize(), depth + 1, visited, counted));
        }
        return new MavenModule(artifactId, selector(root, real), profiles, children);
    }

    private static Path realDirectory(Path directory) {
        try {
            return directory.toRealPath();
        } catch (IOException unreadable) {
            throw new ProjectReadException("module " + directory.getFileName() + " cannot be read");
        }
    }

    private static Document parse(Path pom) {
        try {
            if (Files.size(pom) > MAX_POM_BYTES) {
                throw new ProjectReadException(pom.getParent().getFileName() + "/pom.xml is larger than 2 MB");
            }
            DocumentBuilder builder = secureBuilder();
            try (InputStream input = Files.newInputStream(pom)) {
                return builder.parse(input);
            }
        } catch (IOException | SAXException | ParserConfigurationException failure) {
            throw new ProjectReadException(describe(pom, failure));
        }
    }

    private static DocumentBuilder secureBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler());
        return builder;
    }

    private static String describe(Path pom, Exception failure) {
        String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return "cannot parse " + pom.getParent().getFileName() + "/pom.xml: " + detail.lines().findFirst().orElse("");
    }

    private static List<String> moduleNames(Element project) {
        List<String> names = new ArrayList<>();
        for (Element modules : childElements(project, "modules")) {
            for (Element module : childElements(modules, "module")) {
                String name = module.getTextContent().trim();
                if (name.isEmpty()) {
                    throw new ProjectReadException("a module entry is empty");
                }
                names.add(name);
            }
        }
        return names;
    }

    private static List<String> profileIds(Element project) {
        Set<String> ids = new LinkedHashSet<>();
        for (Element profiles : childElements(project, "profiles")) {
            for (Element profile : childElements(profiles, "profile")) {
                childText(profile, "id").ifPresent(ids::add);
            }
        }
        return List.copyOf(ids);
    }

    private static Optional<String> childText(Element parent, String name) {
        return childElements(parent, name).stream()
                .map(element -> element.getTextContent().trim())
                .filter(text -> !text.isEmpty())
                .findFirst();
    }

    private static List<Element> childElements(Element parent, String name) {
        List<Element> found = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getTagName())) {
                found.add(element);
            }
        }
        return found;
    }

    private static String selector(Path root, Path real) {
        return real.equals(root) ? "" : root.relativize(real).toString().replace('\\', '/');
    }

    private static final class ProjectReadException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ProjectReadException(String message) {
            super(message);
        }
    }
}
