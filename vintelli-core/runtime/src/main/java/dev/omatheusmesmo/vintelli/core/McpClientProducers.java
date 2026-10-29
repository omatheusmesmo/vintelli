package dev.omatheusmesmo.vintelli.core;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the MCP Java SDK JSON mapper and schema validator explicitly. The SDK
 * otherwise resolves both through {@code ServiceLoader}, which is disabled in
 * Quarkus native images. The mapper is a dedicated Jackson 3 instance, not the
 * Quarkus Jackson 2 {@code ObjectMapper}.
 */
@Singleton
public final class McpClientProducers {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Produces
    @Singleton
    @DefaultBean
    McpJsonMapper mcpJsonMapper() {
        return new JacksonMcpJsonMapper(jsonMapper);
    }

    @Produces
    @Singleton
    @DefaultBean
    JsonSchemaValidator mcpJsonSchemaValidator() {
        return new DefaultJsonSchemaValidator(jsonMapper);
    }
}
