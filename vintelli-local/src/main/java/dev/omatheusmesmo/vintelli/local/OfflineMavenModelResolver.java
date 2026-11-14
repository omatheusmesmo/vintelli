package dev.omatheusmesmo.vintelli.local;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.building.StringModelSource;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.model.Model;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.LocalRepositoryManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Resolves Maven parents and imported BOMs from the workspace root or local cache only. */
final class OfflineMavenModelResolver implements ModelResolver {

    private final Path projectRoot;
    private final Path localRepository;
    private final Model rootModel;
    private final LocalRepositoryManager repositoryManager;

    OfflineMavenModelResolver(Path projectRoot, Path localRepository, Model rootModel,
            LocalRepositoryManager repositoryManager) {
        this.projectRoot = canonicalBase(projectRoot);
        this.localRepository = canonicalBase(localRepository);
        this.rootModel = rootModel;
        this.repositoryManager = repositoryManager;
    }

    private static Path canonicalBase(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try {
            return normalized.toRealPath();
        } catch (IOException unavailable) {
            return normalized;
        }
    }

    @Override
    public ModelSource resolveModel(String groupId, String artifactId, String version)
            throws UnresolvableModelException {
        Path pom = workspaceRootPom(groupId, artifactId, version);
        if (pom == null) {
            Path candidate = localPom(groupId, artifactId, version);
            try {
                pom = candidate.toRealPath();
            } catch (IOException unavailable) {
                throw new UnresolvableModelException(unavailable, groupId, artifactId, version);
            }
            if (!pom.startsWith(localRepository)) {
                throw new UnresolvableModelException("Resolved POM is outside the local repository", groupId,
                        artifactId, version);
            }
        }
        try {
            return new StringModelSource(MavenProjectDependencyGraphProvider.readPomText(pom), pom.toUri().toString());
        } catch (IOException unavailable) {
            throw new UnresolvableModelException(unavailable, groupId, artifactId, version);
        }
    }

    private Path workspaceRootPom(String groupId, String artifactId, String version) {
        if (Objects.equals(groupId, rootModel.getGroupId())
                && Objects.equals(artifactId, rootModel.getArtifactId())
                && Objects.equals(version, rootModel.getVersion())) {
            try {
                Path pom = projectRoot.resolve("pom.xml").toRealPath();
                return pom.startsWith(projectRoot) ? pom : null;
            } catch (IOException unavailable) {
                return null;
            }
        }
        return null;
    }

    private Path localPom(String groupId, String artifactId, String version) throws UnresolvableModelException {
        if (!safeCoordinate(groupId) || !safeCoordinate(artifactId) || !safeCoordinate(version)) {
            throw new UnresolvableModelException("Invalid Maven coordinates", groupId, artifactId, version);
        }
        var artifact = new DefaultArtifact(groupId, artifactId, "", "pom", version);
        return repositoryManager.getRepository().getBasedir().toPath()
                .resolve(repositoryManager.getPathForLocalArtifact(artifact)).normalize();
    }

    private static boolean safeCoordinate(String value) {
        return value != null && value.matches("[A-Za-z0-9_.+-]+") && !value.contains("..")
                && !value.startsWith(".") && !value.endsWith(".");
    }

    @Override
    public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
        return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
    }

    @Override
    public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
        return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
    }

    @Override
    public void addRepository(Repository repository) throws InvalidRepositoryException {
        // Never add remote repositories to this resolver.
    }

    @Override
    public void addRepository(Repository repository, boolean replace) throws InvalidRepositoryException {
        // Never add remote repositories to this resolver.
    }

    @Override
    public ModelResolver newCopy() {
        return new OfflineMavenModelResolver(projectRoot, localRepository, rootModel, repositoryManager);
    }
}
