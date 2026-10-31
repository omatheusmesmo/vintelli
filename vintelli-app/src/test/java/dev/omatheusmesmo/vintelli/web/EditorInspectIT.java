package dev.omatheusmesmo.vintelli.web;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;

/** Exercises editor bridge file operations through the gated headless surface in the packaged app. */
@QuarkusIntegrationTest
@WithTestResource(value = EditorInspectEnabledResource.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)
class EditorInspectIT extends EditorInspectTest {}
