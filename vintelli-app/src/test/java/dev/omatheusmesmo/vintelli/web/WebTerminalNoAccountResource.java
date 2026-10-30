package dev.omatheusmesmo.vintelli.web;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Surface switched on with no account configured at all: the embedded realm
 * stays empty, so no credential can ever pass the basic gate.
 */
public final class WebTerminalNoAccountResource implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("vintelli.web.enabled", "true");
    }
}
