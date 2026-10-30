package dev.omatheusmesmo.vintelli.core.deployment;

import java.util.ArrayList;
import java.util.List;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationIndexBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;

/**
 * Build steps for the AEsh command layer reflection needs, derived from the
 * build indexes instead of hand written files. The {@code aesh-processor}
 * generates the command metadata, and its registries are discovered at runtime
 * through the {@code META-INF/aesh/registry} resource or the ServiceLoader
 * fallback: both keep the resources and the registry classes reachable in the
 * native image. The REPL also discovers the terminal providers through the
 * ServiceLoader, so the same derivation keeps them reachable.
 */
final class AeshCommandProcessor {

    private static final DotName METADATA_REGISTRY =
            DotName.createSimple("org.aesh.command.metadata.MetadataRegistry");
    private static final DotName TERMINAL_PROVIDER =
            DotName.createSimple("org.aesh.terminal.provider.TerminalProvider");

    @BuildStep
    IndexDependencyBuildItem indexAesh() {
        return new IndexDependencyBuildItem("org.aesh", "aesh");
    }

    @BuildStep
    ReflectiveClassBuildItem metadataRegistries(CombinedIndexBuildItem combined,
            ApplicationIndexBuildItem application) {
        List<ClassInfo> registries = knownImplementors(combined, application, METADATA_REGISTRY);
        return ReflectiveClassBuildItem.builder(namesOf(registries).toArray(String[]::new)).constructors().build();
    }

    @BuildStep
    ReflectiveClassBuildItem terminalProviders(CombinedIndexBuildItem combined,
            ApplicationIndexBuildItem application) {
        List<ClassInfo> providers = knownImplementors(combined, application, TERMINAL_PROVIDER);
        return ReflectiveClassBuildItem.builder(namesOf(providers).toArray(String[]::new)).constructors().build();
    }

    @BuildStep
    NativeImageResourceBuildItem aeshDiscoveryResources() {
        return new NativeImageResourceBuildItem(
                "META-INF/aesh/registry",
                "META-INF/services/org.aesh.command.metadata.MetadataRegistry",
                "META-INF/services/org.aesh.terminal.provider.TerminalProvider");
    }

    private static List<ClassInfo> knownImplementors(CombinedIndexBuildItem combined,
            ApplicationIndexBuildItem application, DotName type) {
        List<ClassInfo> implementors = new ArrayList<>();
        implementors.addAll(combined.getIndex().getAllKnownImplementors(type));
        implementors.addAll(application.getIndex().getAllKnownImplementors(type));
        return implementors;
    }

    private static List<String> namesOf(List<ClassInfo> classes) {
        List<String> names = new ArrayList<>(classes.size());
        for (ClassInfo info : classes) {
            names.add(info.name().toString());
        }
        return names;
    }
}
