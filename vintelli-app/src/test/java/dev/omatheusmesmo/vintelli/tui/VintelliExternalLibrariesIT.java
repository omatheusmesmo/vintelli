package dev.omatheusmesmo.vintelli.tui;

import dev.omatheusmesmo.vintelli.web.ExternalLibrariesResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
@WithTestResource(value = ExternalLibrariesResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class VintelliExternalLibrariesIT extends VintelliExternalLibrariesTest {
}
