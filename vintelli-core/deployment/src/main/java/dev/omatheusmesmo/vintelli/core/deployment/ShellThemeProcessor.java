package dev.omatheusmesmo.vintelli.core.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourcePatternsBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import org.jboss.jandex.ClassInfo;

import java.util.List;

/**
 * Build steps for the shell theme stylesheets and the inspection payloads of
 * the TUI. The stylesheets are runtime classpath resources the CSS engine reads
 * with {@code getResourceAsStream}, so every shipped {@code .tcss} under the
 * theme resource directory is included in the native image by pattern; adding
 * a third theme needs no deployment change. The payload of a rejected theme
 * switch is an untyped {@code Response} entity that the native build cannot
 * see, so its record is registered for reflection from the Jandex index.
 */
final class ShellThemeProcessor {

    private static final String THEME_REJECTED = "dev.omatheusmesmo.vintelli.tui.ShellInspectResource$ThemeRejected";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem("vintelli-tui-themes");
    }

    @BuildStep
    NativeImageResourcePatternsBuildItem shellThemeStylesheets() {
        return NativeImageResourcePatternsBuildItem.builder()
                .includeGlob("dev/omatheusmesmo/vintelli/tui/themes/*.tcss")
                .build();
    }

    @BuildStep
    ReflectiveClassBuildItem themeRejectedPayload(CombinedIndexBuildItem index) {
        List<String> names = index.getIndex().getKnownClasses().stream()
                .map(ClassInfo::name)
                .map(Object::toString)
                .filter(THEME_REJECTED::equals)
                .toList();
        return ReflectiveClassBuildItem.builder(names.toArray(String[]::new))
                .constructors()
                .methods()
                .fields()
                .build();
    }
}
