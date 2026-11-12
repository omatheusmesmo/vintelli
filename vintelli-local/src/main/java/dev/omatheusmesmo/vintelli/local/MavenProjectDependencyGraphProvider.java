package dev.omatheusmesmo.vintelli.local;

import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraph;
import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraph.LibraryDependency;
import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraph.Disposition;
import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraph.Module;
import dev.omatheusmesmo.vintelli.api.dependencies.ProjectDependencyGraphProvider;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import org.eclipse.aether.DefaultRepositorySystemSession;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.aether.artifact.DefaultArtifact;

/** Resolves Maven dependency metadata from the offline local repository only. */
@Singleton
public final class MavenProjectDependencyGraphProvider implements ProjectDependencyGraphProvider {

    private static final int MAX_POM_BYTES = 2 * 1024 * 1024;
    private static final int MAX_MODULES = 500;
    private static final int MAX_GRAPH_NODES = 10_000;
    private final Path localRepository;

    @Inject
    public MavenProjectDependencyGraphProvider() {
        this(Path.of(System.getProperty("user.home"), ".m2", "repository"));
    }

    MavenProjectDependencyGraphProvider(Path localRepository) {
        this.localRepository = localRepository.toAbsolutePath().normalize();
    }

    @Override
    public boolean supports(Path projectRoot) {
        return projectRoot != null && Files.isRegularFile(projectRoot.resolve("pom.xml"));
    }

    @Override
    public ProjectDependencyGraph read(Path projectRoot) {
        try {
            Path root = projectRoot.toRealPath();
            Path pom = confined(root, root.resolve("pom.xml"));
            if (pom == null) {
                return failed("The Maven project file is outside the project root");
            }
            List<String> missing = new ArrayList<>();
            Resolver resolver = resolver();
            Model rootRawModel = readModel(pom);
            Model rootModel = buildEffectiveModel(pom, rootRawModel, root, rootRawModel, resolver.local(), missing);
            List<Module> modules = new ArrayList<>();
            int[] count = {0};
            collectModules(root, root, rootModel, resolver, new HashSet<>(), modules, missing, count);
            if (modules.isEmpty()) {
                modules.add(new Module(displayName(root, rootModel), List.of()));
            }
            return new ProjectDependencyGraph(modules, missing.isEmpty(),
                    missing.isEmpty() ? "Offline dependency metadata" : "Incomplete offline graph: "
                            + String.join(", ", missing.stream().limit(5).toList()));
        } catch (Exception failure) {
            return failed(message(failure));
        }
    }

    private void collectModules(Path root, Path directory, Model rootModel, Resolver resolver, Set<Path> visited,
            List<Module> modules, List<String> missing, int[] count) {
        if (count[0]++ >= MAX_MODULES) {
            missing.add("module limit reached");
            return;
        }
        try {
            Path realDirectory = directory.toRealPath();
            if (!realDirectory.startsWith(root) || !visited.add(realDirectory)) {
                missing.add("module path is outside the project or repeated");
                return;
            }
            Path pom = confined(root, realDirectory.resolve("pom.xml"));
            if (pom == null) {
                missing.add(realDirectory.getFileName() + "/pom.xml");
                return;
            }
            Model model;
            if (realDirectory.equals(root)) {
                model = rootModel;
            } else {
                Model rawModel = readModel(pom);
                model = buildEffectiveModel(pom, rawModel, root, rootModel, resolver.local(), missing);
            }
            List<LibraryDependency> dependencies = dependencies(model, resolver, missing);
            modules.add(new Module(displayName(realDirectory, model), dependencies));
            for (String child : model.getModules()) {
                if (child == null || child.isBlank()) {
                    continue;
                }
                Path childDirectory = realDirectory.resolve(child).normalize();
                if (!childDirectory.startsWith(root)) {
                    missing.add("module path escapes the project root");
                } else {
                    collectModules(root, childDirectory, rootModel, resolver, visited, modules, missing, count);
                }
            }
        } catch (Exception failure) {
            missing.add(directory.getFileName() + ": " + message(failure));
        }
    }

    private List<LibraryDependency> dependencies(Model model, Resolver resolver, List<String> missing) {
        List<Dependency> roots = new ArrayList<>();
        for (var dependency : model.getDependencies()) {
            if ("test".equals(dependency.getScope()) && "pom".equals(dependency.getType())) {
                continue;
            }
            String group = dependency.getGroupId();
            String artifact = dependency.getArtifactId();
            String version = dependency.getVersion();
            String scope = dependency.getScope();
            if (group == null || artifact == null || version == null || version.isBlank() || version.contains("${")) {
                missing.add("version unavailable for " + group + ":" + artifact);
                continue;
            }
            String type = dependency.getType();
            String classifier = dependency.getClassifier();
            ArtifactType artifactType = resolver.session().getArtifactTypeRegistry().get(type == null ? "jar" : type);
            String extension = artifactType == null ? type : artifactType.getExtension();
            String artifactClassifier = classifier == null || classifier.isBlank()
                    ? artifactType == null ? "" : artifactType.getClassifier()
                    : classifier;
            roots.add(new Dependency(new DefaultArtifact(group, artifact, artifactClassifier,
                    extension == null || extension.isBlank() ? "jar" : extension, version),
                    scope == null || scope.isBlank() ? "compile" : scope, dependency.isOptional()));
        }
        if (roots.isEmpty()) {
            return List.of();
        }
        try {
            CollectRequest request = new CollectRequest().setDependencies(roots).setRepositories(List.of());
            CollectResult result = resolver.system().collectDependencies(resolver.session(), request);
            Map<String, String> winners = winners(result.getRoot());
            List<LibraryDependency> graph = new ArrayList<>();
            for (DependencyNode node : result.getRoot().getChildren()) {
                graph.add(convert(node, resolver.local(), winners, new int[]{0}, missing));
            }
            return List.copyOf(graph);
        } catch (DependencyCollectionException unavailable) {
            missing.add("one or more dependency POMs are not cached");
            List<LibraryDependency> partial = new ArrayList<>();
            for (Dependency dependency : roots) {
                var artifact = dependency.getArtifact();
                partial.add(new LibraryDependency(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(),
                        dependency.getScope(), Disposition.MISSING, null, List.of(), dependency.isOptional()));
            }
            return List.copyOf(partial);
        } catch (Exception failure) {
            missing.add("dependency resolver: " + message(failure));
            return List.of();
        }
    }

    private static Map<String, String> winners(DependencyNode root) {
        Map<String, String> winners = new HashMap<>();
        List<DependencyNode> level = new ArrayList<>(root.getChildren());
        while (!level.isEmpty()) {
            List<DependencyNode> next = new ArrayList<>();
            for (DependencyNode node : level) {
                if (node.getArtifact() != null) {
                    var artifact = node.getArtifact();
                    winners.putIfAbsent(artifactKey(artifact), artifact.getVersion());
                }
                next.addAll(node.getChildren());
            }
            level = next;
        }
        return winners;
    }

    private static String artifactKey(Artifact artifact) {
        return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getExtension() + ":"
                + Objects.requireNonNullElse(artifact.getClassifier(), "");
    }

    private static LibraryDependency convert(DependencyNode node, LocalRepositoryManager local,
            Map<String, String> winners,
            int[] count,
            List<String> missing) {
        if (count[0]++ >= MAX_GRAPH_NODES || node.getArtifact() == null) {
            missing.add("dependency graph limit reached");
            return new LibraryDependency("", "additional dependencies", "…", "", Disposition.MISSING, null,
                    List.of());
        }
        var artifact = node.getArtifact();
        Object winner = node.getData().get(ConflictResolver.NODE_DATA_WINNER);
        boolean omitted = winner instanceof DependencyNode winningNode && winningNode != node
                || !artifact.getVersion().equals(winners.get(artifactKey(artifact)));
        Path jar = localPath(local, artifact);
        Disposition disposition = omitted ? Disposition.OMITTED
                : jar != null && Files.isRegularFile(jar) ? Disposition.RESOLVED : Disposition.MISSING;
        if (disposition == Disposition.MISSING) {
            missing.add(artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion());
        }
        List<LibraryDependency> children = new ArrayList<>();
        boolean optional = node.getDependency() != null && node.getDependency().isOptional();
        if (!optional) {
            for (DependencyNode child : node.getChildren()) {
                children.add(convert(child, local, winners, count, missing));
            }
        }
        return new LibraryDependency(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(),
                node.getDependency() == null ? "compile" : node.getDependency().getScope(), disposition,
                disposition == Disposition.RESOLVED ? jar : null, children, optional);
    }

    private Resolver resolver() {
        RepositorySystem system = new OfflineRepositorySystemSupplier().get();
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();
        session.setOffline(true);
        session.setConfigProperty(ConflictResolver.CONFIG_PROP_VERBOSE, true);
        session.setConfigProperty("aether.syncContext.named.nameMapper", "gav");
        LocalRepositoryManager manager = system.newLocalRepositoryManager(session, new LocalRepository(localRepository.toFile()));
        session.setLocalRepositoryManager(manager);
        return new Resolver(system, session, manager);
    }

    private static Path localPath(LocalRepositoryManager manager, Artifact artifact) {
        try {
            Path path = manager.getRepository().getBasedir().toPath().resolve(manager.getPathForLocalArtifact(artifact));
            return path.normalize();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static Model readModel(Path pom) throws IOException, XmlPullParserException {
        return new MavenXpp3Reader().read(new StringReader(readPomText(pom)));
    }

    static String readPomText(Path pom) throws IOException {
        if (Files.size(pom) > MAX_POM_BYTES) {
            throw new IOException("pom.xml exceeds the size limit");
        }
        String xml = Files.readString(pom, StandardCharsets.UTF_8);
        if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) {
            throw new IOException("DTD declarations are not supported");
        }
        return xml;
    }

    private static Model buildEffectiveModel(Path pom, Model rawModel, Path projectRoot, Model rootModel,
            LocalRepositoryManager repositoryManager, List<String> missing) {
        Model safeRaw = rawModel.clone();
        if (safeRaw.getParent() != null) {
            // Prevent Maven from following relativePath outside the approved workspace/cache roots.
            safeRaw.getParent().setRelativePath("");
        }
        var request = new DefaultModelBuildingRequest()
                .setPomFile(pom.toFile())
                .setRawModel(safeRaw)
                .setModelResolver(new OfflineMavenModelResolver(projectRoot, repositoryManager.getRepository()
                        .getBasedir().toPath(), rootModel, repositoryManager))
                .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
                .setSystemProperties(System.getProperties())
                .setUserProperties(new Properties());
        try {
            var result = new DefaultModelBuilderFactory().newInstance().build(request);
            markInactiveProfileDependencies(result, missing);
            return result.getEffectiveModel();
        } catch (ModelBuildingException unavailable) {
            missing.add(displayName(pom.getParent(), rawModel) + " has an unresolved parent, BOM, or profile: "
                    + message(unavailable));
            Model partial = unavailable.getResult() == null ? null : unavailable.getResult().getEffectiveModel();
            return partial == null ? safeRaw : partial;
        }
    }

    private static void markInactiveProfileDependencies(ModelBuildingResult result, List<String> missing) {
        for (String modelId : result.getModelIds()) {
            Model raw = result.getRawModel(modelId);
            if (raw == null || raw.getProfiles().isEmpty()) {
                continue;
            }
            Set<String> active = result.getActivePomProfiles(modelId).stream()
                    .map(profile -> profile.getId()).collect(Collectors.toSet());
            for (var profile : raw.getProfiles()) {
                boolean contributes = !profile.getDependencies().isEmpty()
                        || profile.getDependencyManagement() != null
                                && !profile.getDependencyManagement().getDependencies().isEmpty();
                if (contributes && !active.contains(profile.getId())) {
                    missing.add("inactive profile " + profile.getId() + " may contribute dependencies");
                }
            }
        }
    }

    private static Path confined(Path root, Path candidate) throws IOException {
        Path real = candidate.toRealPath();
        return real.startsWith(root) ? real : null;
    }

    private static String displayName(Path directory, Model model) {
        String artifact = model.getArtifactId();
        return artifact == null || artifact.isBlank() ? directory.getFileName().toString() : artifact;
    }

    private static ProjectDependencyGraph failed(String reason) {
        return new ProjectDependencyGraph(List.of(), false, reason);
    }

    private static String message(Throwable failure) {
        String value = failure.getMessage();
        return value == null || value.isBlank() ? failure.getClass().getSimpleName() : value;
    }

    private record Resolver(RepositorySystem system, RepositorySystemSession session,
            LocalRepositoryManager local) {
    }

}
