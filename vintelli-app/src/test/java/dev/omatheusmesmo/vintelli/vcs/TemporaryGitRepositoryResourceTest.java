package dev.omatheusmesmo.vintelli.vcs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class TemporaryGitRepositoryResourceTest {

    @Test
    void stopDeletesAPlainRepositoryWithoutFailing() {
        TemporaryGitRepositoryResource resource = new TemporaryGitRepositoryResource();
        resource.start();
        Path root = TemporaryGitRepositoryResource.repositoryRoot();

        assertDoesNotThrow(resource::stop);

        assertFalse(Files.exists(root), "the repository must be deleted");
        assertNull(TemporaryGitRepositoryResource.repositoryRoot(), "the resource must forget the repository");
    }
}
