package dev.omatheusmesmo.vintelli.core.deployment;

import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.CommitConfig;
import org.eclipse.jgit.lib.CoreConfig;
import org.eclipse.jgit.lib.GcConfig;
import org.eclipse.jgit.lib.GpgConfig;
import org.eclipse.jgit.lib.IndexDiff;
import org.eclipse.jgit.lib.ObjectChecker;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.SignatureVerifier;
import org.eclipse.jgit.lib.SubmoduleConfig;
import org.eclipse.jgit.transport.HttpConfig;
import org.eclipse.jgit.util.FileUtils;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBundleBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;

/** Native-image registrations for the JGit APIs used by the local provider. */
final class JGitProcessor {

    private static final String HTTP_AUTH_DIGEST = HttpConfig.class.getPackageName() + ".HttpAuthMethod$Digest";

    @BuildStep
    ReflectiveClassBuildItem jgitTypes() {
        return ReflectiveClassBuildItem.builder(
                BranchConfig.BranchRebaseMode.class,
                CommitConfig.CleanupMode.class,
                CoreConfig.AutoCRLF.class,
                CoreConfig.CheckStat.class,
                CoreConfig.EOL.class,
                CoreConfig.EolStreamType.class,
                CoreConfig.HideDotFiles.class,
                CoreConfig.LogRefUpdates.class,
                CoreConfig.SymLinks.class,
                CoreConfig.TrustStat.class,
                DiffAlgorithm.SupportedAlgorithm.class,
                DirCache.DirCacheVersion.class,
                GcConfig.PackRefsMode.class,
                GpgConfig.GpgFormat.class,
                HttpConfig.HttpRedirectMode.class,
                IndexDiff.StageState.class,
                JGitText.class,
                ObjectChecker.ErrorType.class,
                MergeCommand.ConflictStyle.class,
                MergeCommand.FastForwardMode.Merge.class,
                MergeCommand.FastForwardMode.class,
                Ref.Storage.class,
                RefUpdate.Result.class,
                SignatureVerifier.TrustLevel.class,
                SubmoduleConfig.FetchRecurseSubmodulesMode.class)
                .fields()
                .methods()
                .build();
    }

    @BuildStep
    void initializeJGitAtRuntime(BuildProducer<RuntimeInitializedClassBuildItem> initializedClasses) {
        initializedClasses.produce(new RuntimeInitializedClassBuildItem(WindowCache.class.getName()));
        initializedClasses.produce(new RuntimeInitializedClassBuildItem(HTTP_AUTH_DIGEST));
        initializedClasses.produce(new RuntimeInitializedClassBuildItem(FileUtils.class.getName()));
    }

    @BuildStep
    NativeImageResourceBundleBuildItem includeJGitMessages() {
        return new NativeImageResourceBundleBuildItem(JGitText.class.getName());
    }
}
