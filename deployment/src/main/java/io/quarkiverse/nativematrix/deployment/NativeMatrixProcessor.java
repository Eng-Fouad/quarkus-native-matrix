package io.quarkiverse.nativematrix.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.logging.Logger;

import io.quarkiverse.nativematrix.deployment.orchestrator.CheckPolicy;
import io.quarkiverse.nativematrix.deployment.orchestrator.FullBuildPlanner;
import io.quarkiverse.nativematrix.deployment.orchestrator.LocalSnapshots;
import io.quarkiverse.nativematrix.deployment.orchestrator.MatrixTarget;
import io.quarkiverse.nativematrix.deployment.orchestrator.NativeMatrixOrchestrator;
import io.quarkiverse.nativematrix.deployment.orchestrator.NativeSources;
import io.quarkiverse.nativematrix.deployment.orchestrator.Packager;
import io.quarkiverse.nativematrix.deployment.orchestrator.RunnerRegistry;
import io.quarkiverse.nativematrix.deployment.runners.LocalRunner;
import io.quarkiverse.nativematrix.deployment.signing.AppleSigner;
import io.quarkiverse.nativematrix.deployment.signing.AuthenticodeSigner;
import io.quarkiverse.nativematrix.deployment.signing.ExecutableSigner;
import io.quarkiverse.nativematrix.spi.BuildMode;
import io.quarkiverse.nativematrix.spi.FullBuild;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.RunnerOptions;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Produce;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.pkg.NativeConfig;
import io.quarkus.deployment.pkg.builditem.ArtifactResultBuildItem;
import io.quarkus.deployment.pkg.builditem.BuildSystemTargetBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.pkg.steps.NativeSourcesBuild;
import io.quarkus.deployment.sbom.SbomBuildItem;
import io.quarkus.maven.dependency.ResolvedDependency;

class NativeMatrixProcessor {

    private static final Logger LOG = Logger.getLogger(NativeMatrixProcessor.class);
    static final String FEATURE = "native-matrix";
    static final String NATIVE_SOURCES_ARTIFACT_TYPE = "native-sources";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * Runs once Quarkus has written {@code target/native-sources}.
     * <p>
     * Quarkus writes the native sources in the step producing the {@code native-sources} {@link ArtifactResultBuildItem}.
     * Consuming all {@link ArtifactResultBuildItem}s orders this step after it, but this step cannot produce one itself
     * (that would be a cycle). It declares {@link SbomBuildItem} instead, the other multi build item requested at the end
     * of every production build, so that the build chain always executes it - exactly like SBOM generators, which also
     * run after all the artifacts were produced. No SBOM is actually produced.
     */
    @BuildStep(onlyIf = NativeSourcesBuild.class)
    @Produce(SbomBuildItem.class)
    void buildMatrix(NativeMatrixConfig config, BuildSystemTargetBuildItem buildSystemTarget,
            List<ArtifactResultBuildItem> artifacts, CurateOutcomeBuildItem curateOutcome) {
        if (!config.enabled() || config.target().isEmpty()) {
            return;
        }
        if (artifacts.stream().noneMatch(a -> NATIVE_SOURCES_ARTIFACT_TYPE.equals(a.getType()))) {
            LOG.warn("No native sources were produced, skipping the native matrix");
            return;
        }
        List<MatrixTarget> targets = resolveTargets(config, Platform.host());
        if (targets.isEmpty()) {
            LOG.info("All native matrix targets are disabled or filtered out");
            return;
        }
        Path outputDir = buildSystemTarget.getOutputDirectory();
        try {
            NativeSources sources = NativeSources.read(outputDir.resolve("native-sources"));
            FullBuild fullBuild = targets.stream().anyMatch(t -> t.mode() == BuildMode.FULL)
                    ? planFullBuild(config, outputDir, dependencies(curateOutcome))
                    : null;
            List<ExecutableSigner> signers = signers(config.signing(), targets);
            Path matrixDir = outputDir.resolve(config.outputDirectory());
            NativeMatrixOrchestrator orchestrator = new NativeMatrixOrchestrator(RunnerRegistry.discover(),
                    new NativeMatrixOrchestrator.Settings(matrixDir,
                            config.parallelism().orElse(targets.size()), config.failFast(),
                            config.keepWorkDirectories(), config.quiet(), Platform.host(),
                            policy(config.platformDependencyCheck()),
                            config.ignoredPlatformDependencies().orElse(List.of()),
                            policy(config.graalvmVersionCheck()), config.reuseUnchanged(), signers));
            NativeMatrixOrchestrator.MatrixResult result = orchestrator.run(sources, targets, fullBuild);

            NativeMatrixConfig.PackageConfig packaging = config.packaging();
            if (packaging.archives() || packaging.jreleaser()) {
                String name = packaging.name().orElse(sources.outputName().replaceFirst("-runner$", ""));
                Packager.Packages packages = Packager.archive(result, matrixDir, name);
                if (packaging.jreleaser()) {
                    Path jreleaser = Packager.writeJReleaserConfig(packages, matrixDir, name);
                    LOG.infof("JReleaser configuration written to %s", jreleaser);
                }
            }

            if (!result.succeeded() && config.failOnError()) {
                List<String> failed = result.targets().stream().filter(r -> !r.succeeded())
                        .map(r -> r.target().name() + ": " + r.error().orElse(r.status().name())).toList();
                throw new IllegalStateException("Native matrix build failed for " + failed.size() + " of "
                        + result.targets().size() + " target(s):\n  - " + String.join("\n  - ", failed)
                        + "\nSee " + result.manifest());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BuildStep(onlyIfNot = NativeSourcesBuild.class)
    @Produce(SbomBuildItem.class)
    void warnWhenNotSourcesOnly(NativeConfig nativeConfig, NativeMatrixConfig config) {
        if (nativeConfig.enabled() && config.enabled() && !config.target().isEmpty()) {
            LOG.warn("Native matrix targets are configured but quarkus.native.sources-only is not enabled: only the"
                    + " native executable for the build machine is built. Build with -Dnative-matrix"
                    + " (or -Dquarkus.native-matrix.build=true) to build the matrix.");
        }
    }

    static FullBuild planFullBuild(NativeMatrixConfig config, Path outputDir, List<LocalSnapshots.Artifact> dependencies) {
        NativeMatrixConfig.FullBuildConfig c = config.fullBuild();
        if (c.posixCommand().isPresent() != c.windowsCommand().isPresent()) {
            throw new IllegalArgumentException("Set both quarkus.native-matrix.full-build.posix-command and"
                    + " quarkus.native-matrix.full-build.windows-command, or none");
        }
        FullBuildPlanner.Settings settings = new FullBuildPlanner.Settings(
                c.projectDirectory().map(Path::of),
                c.buildTool().map(t -> FullBuildPlanner.BuildTool.valueOf(t.name())),
                c.posixCommand(), c.windowsCommand(), c.args().orElse(List.of()), c.skipTests(),
                Set.copyOf(c.excludedDirectories()), c.shipSnapshots(), dependencies, java.util.Optional.empty());
        FullBuild plan = FullBuildPlanner.plan(outputDir, settings);
        LOG.debugf("Full build of %s: %s", plan.projectDirectory(), plan.posixCommand());
        long snapshots = plan.additionalFiles().keySet().stream().filter(k -> k.endsWith(".pom")).count();
        if (snapshots > 0) {
            LOG.infof("Shipping %d SNAPSHOT artifact(s) installed on this machine with the project (full mode)",
                    snapshots);
        }
        return plan;
    }

    static List<LocalSnapshots.Artifact> dependencies(CurateOutcomeBuildItem curateOutcome) {
        List<LocalSnapshots.Artifact> result = new ArrayList<>();
        for (ResolvedDependency d : curateOutcome.getApplicationModel().getDependencies()) {
            if (!d.isSnapshot() || d.getWorkspaceModule() != null || d.getResolvedPaths().isEmpty()) {
                continue;
            }
            Path file = d.getResolvedPaths().iterator().next();
            if (java.nio.file.Files.isRegularFile(file)) {
                result.add(new LocalSnapshots.Artifact(d.getGroupId(), d.getArtifactId(), d.getVersion(), file));
            }
        }
        return result;
    }

    /**
     * Creates the signers and checks their configuration, so that a wrong password fails the build before any target
     * is built.
     */
    static List<ExecutableSigner> signers(NativeMatrixConfig.SigningConfig signing, List<MatrixTarget> targets) {
        List<ExecutableSigner> signers = new ArrayList<>();
        NativeMatrixConfig.WindowsSigningConfig w = signing.windows();
        boolean windows = targets.stream().anyMatch(t -> t.platform().os() == Platform.Os.WINDOWS);
        if (w.keystore().isPresent() && windows) {
            AuthenticodeSigner signer = new AuthenticodeSigner(w.keystore().get(), w.storetype(), w.storepass(),
                    w.alias(), w.keypass(), w.certfile(), w.timestamp(), w.tsaurl(), w.programName(), w.programUrl());
            try {
                signer.loadKeyStore();
            } catch (Exception e) {
                throw new IllegalArgumentException("Cannot open the Authenticode key store " + w.keystore().get()
                        + " (quarkus.native-matrix.signing.windows.*): " + e.getMessage(), e);
            }
            signers.add(signer);
        }
        NativeMatrixConfig.MacosSigningConfig m = signing.macos();
        boolean macos = targets.stream().anyMatch(t -> t.platform().os() == Platform.Os.MACOS);
        if (m.p12File().isPresent() && macos) {
            AppleSigner signer = new AppleSigner(m.command(), m.p12File().get(), m.p12Password(), m.entitlements(),
                    m.notarize(), m.apiKeyFile());
            signer.validate();
            signers.add(signer);
        }
        return signers;
    }

    static CheckPolicy policy(NativeMatrixConfig.Check check) {
        return CheckPolicy.valueOf(check.name());
    }

    static List<MatrixTarget> resolveTargets(NativeMatrixConfig config, Platform host) {
        Set<String> only = new LinkedHashSet<>(config.only().orElse(Collections.emptyList()));
        for (String name : only) {
            if (!config.target().containsKey(name)) {
                throw new IllegalArgumentException("quarkus.native-matrix.only references unknown target '" + name
                        + "', configured targets: " + config.target().keySet());
            }
        }
        List<MatrixTarget> targets = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, NativeMatrixConfig.TargetConfig> e : config.target().entrySet()) {
            String name = e.getKey();
            NativeMatrixConfig.TargetConfig t = e.getValue();
            if (!t.enabled() || (!only.isEmpty() && !only.contains(name))) {
                continue;
            }
            Platform platform;
            try {
                platform = t.platform().map(Platform::parse).orElseGet(() -> Platform.tryParse(name).orElseThrow(
                        () -> new IllegalArgumentException("set quarkus.native-matrix.target.\"" + name
                                + "\".platform, the name is not a platform such as linux-amd64")));
            } catch (IllegalArgumentException ex) {
                problems.add(name + ": " + ex.getMessage());
                continue;
            }
            String runnerId = t.runner().orElse(platform.equals(host) ? LocalRunner.ID : null);
            if (runnerId == null) {
                problems.add(name + ": set quarkus.native-matrix.target.\"" + name + "\".runner (local, docker, ssh,"
                        + " github-actions or a custom runner)");
                continue;
            }
            RunnerOptions options = RunnerOptions.of(config.runner().getOrDefault(runnerId, Map.of()))
                    .with(t.options());
            BuildMode mode = BuildMode.valueOf(t.mode().orElse(config.mode()).name());
            targets.add(new MatrixTarget(name, platform, runnerId, options, t.timeout().orElse(config.timeout()), mode));
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid native matrix configuration:\n  - " + String.join("\n  - ", problems));
        }
        targets.sort((a, b) -> a.name().compareTo(b.name()));
        return targets;
    }
}
