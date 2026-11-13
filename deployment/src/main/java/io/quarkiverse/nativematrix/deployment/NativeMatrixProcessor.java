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

import io.quarkiverse.nativematrix.deployment.orchestrator.MatrixTarget;
import io.quarkiverse.nativematrix.deployment.orchestrator.NativeMatrixOrchestrator;
import io.quarkiverse.nativematrix.deployment.orchestrator.NativeSources;
import io.quarkiverse.nativematrix.deployment.orchestrator.RunnerRegistry;
import io.quarkiverse.nativematrix.deployment.runners.LocalRunner;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.RunnerOptions;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Produce;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.pkg.NativeConfig;
import io.quarkus.deployment.pkg.builditem.ArtifactResultBuildItem;
import io.quarkus.deployment.pkg.builditem.BuildSystemTargetBuildItem;
import io.quarkus.deployment.pkg.steps.NativeSourcesBuild;
import io.quarkus.deployment.sbom.SbomBuildItem;

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
            List<ArtifactResultBuildItem> artifacts) {
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
            NativeMatrixOrchestrator orchestrator = new NativeMatrixOrchestrator(RunnerRegistry.discover(),
                    new NativeMatrixOrchestrator.Settings(outputDir.resolve(config.outputDirectory()),
                            config.parallelism().orElse(targets.size()), config.failFast(),
                            config.keepWorkDirectories(), config.quiet()));
            NativeMatrixOrchestrator.MatrixResult result = orchestrator.run(sources, targets);
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
                    + " native executable for the build machine is built. Build with -Dquarkus.native.enabled=true"
                    + " -Dquarkus.native.sources-only=true to build the matrix.");
        }
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
            targets.add(new MatrixTarget(name, platform, runnerId, options, t.timeout().orElse(config.timeout())));
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid native matrix configuration:\n  - " + String.join("\n  - ", problems));
        }
        targets.sort((a, b) -> a.name().compareTo(b.name()));
        return targets;
    }
}
