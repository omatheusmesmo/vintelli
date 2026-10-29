package dev.omatheusmesmo.vintelli.core;

import com.agentclientprotocol.sdk.json.AcpJsonMapper;
import com.agentclientprotocol.sdk.json.jackson3.Jackson3AcpJsonMapperSupplier;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Builds the ACP SDK JSON mapper explicitly from the Jackson 3 supplier. The SDK
 * otherwise resolves a supplier through {@code ServiceLoader}
 * ({@code META-INF/services/com.agentclientprotocol.sdk.json.AcpJsonMapperSupplier}),
 * which does not work in Quarkus native images, so the mapper is wired directly.
 */
@Singleton
public final class AcpClientProducers {

    @Produces
    @Singleton
    @DefaultBean
    AcpJsonMapper acpJsonMapper() {
        return new Jackson3AcpJsonMapperSupplier().get();
    }
}
