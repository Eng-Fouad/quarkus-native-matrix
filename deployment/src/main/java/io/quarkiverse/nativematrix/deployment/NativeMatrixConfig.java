package io.quarkiverse.nativematrix.deployment;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigDocSection;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Builds native executables for several OS/architecture combinations from a single Quarkus build.
 * <p>
 * The matrix runs when the application is built with {@code quarkus.native.enabled=true} and
 * {@code quarkus.native.sources-only=true}: Quarkus produces the platform independent native sources once, then every
 * target compiles them with {@code native-image} on a machine of the right platform.
 */
@ConfigMapping(prefix = "quarkus.native-matrix")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface NativeMatrixConfig {

    /**
     * Whether the native matrix build is enabled.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * The targets of the matrix, keyed by a name of your choice. When the name is a platform, such as
     * {@code linux-amd64}, {@code linux-arm64}, {@code macos-arm64} or {@code windows-amd64}, the {@code platform}
     * property can be omitted.
     */
    @ConfigDocMapKey("target-name")
    @ConfigDocSection
    Map<String, TargetConfig> target();

    /**
     * Options shared by all the targets using a given runner, e.g.
     * {@code quarkus.native-matrix.runner.github-actions.repository=my-org/my-app}. Target options override them.
     */
    @ConfigDocMapKey("runner-id")
    Map<String, Map<String, String>> runner();

    /**
     * Only build the targets with these names. Handy on the command line, e.g.
     * {@code -Dquarkus.native-matrix.only=linux-arm64,macos-arm64}.
     */
    Optional<List<String>> only();

    /**
     * Maximum number of targets built at the same time. Defaults to the number of targets.
     */
    OptionalInt parallelism();

    /**
     * Whether to cancel the remaining targets as soon as one target fails.
     */
    @WithDefault("false")
    boolean failFast();

    /**
     * Whether the Quarkus build fails when at least one target fails. When disabled, failures are only reported in the
     * summary and the manifest.
     */
    @WithDefault("true")
    boolean failOnError();

    /**
     * Default maximum duration of the build of one target, including transfers and waiting for remote machines.
     */
    @WithDefault("2H")
    Duration timeout();

    /**
     * Directory receiving the executables, one sub-directory per target, relative to the build output directory
     * ({@code target/} with Maven, {@code build/} with Gradle).
     */
    @WithDefault("native-matrix")
    String outputDirectory();

    /**
     * Keep the per-target local work directories after the build, for troubleshooting.
     */
    @WithDefault("false")
    boolean keepWorkDirectories();

    /**
     * Only log the output of {@code native-image} at DEBUG level. Runner messages are still logged.
     */
    @WithDefault("false")
    boolean quiet();

    interface TargetConfig {

        /**
         * Whether this target is built.
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * The runner building this target: {@code local}, {@code docker}, {@code ssh}, {@code github-actions}, or the id
         * of a custom runner. Defaults to {@code local} when the target platform is the platform of the build machine.
         */
        Optional<String> runner();

        /**
         * The platform of the executable, as {@code <os>-<arch>} with os in {@code linux}, {@code macos},
         * {@code windows} and arch in {@code amd64}, {@code arm64}. Defaults to the target name.
         */
        Optional<String> platform();

        /**
         * Maximum duration of the build of this target. Defaults to {@code quarkus.native-matrix.timeout}.
         */
        Optional<Duration> timeout();

        /**
         * Runner specific options, e.g. {@code quarkus.native-matrix.target.macos-arm64.options.host=mac-mini.lan}. See
         * the documentation of each runner.
         */
        @ConfigDocMapKey("option")
        Map<String, String> options();
    }
}
