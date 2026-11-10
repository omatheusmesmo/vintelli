package dev.omatheusmesmo.vintelli.capabilities;

import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/** Proves the native process runner launches suspended JVMs that a real JDK debugger can attach to. */
@QuarkusIntegrationTest
@WithTestResource(value = DebugLaunchProjectResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class DebugLaunchIT extends DebugLaunchTest { }
