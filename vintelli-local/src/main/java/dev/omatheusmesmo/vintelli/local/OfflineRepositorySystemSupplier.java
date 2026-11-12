package dev.omatheusmesmo.vintelli.local;

import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.ChecksumExtractor;

import java.util.Map;

/** Builds a Resolver system with only file transport available. */
final class OfflineRepositorySystemSupplier extends RepositorySystemSupplier {

    @Override
    protected Map<String, ChecksumExtractor> getChecksumExtractors() {
        return Map.of();
    }

    @Override
    protected Map<String, TransporterFactory> getTransporterFactories(Map<String, ChecksumExtractor> ignored) {
        return Map.of("file", new FileTransporterFactory());
    }
}
