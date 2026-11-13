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

    /**
     * Turns a regular package build into a matrix build, without passing {@code quarkus.native.enabled=true} and
     * {@code quarkus.native.sources-only=true} by hand: {@code mvn package -Dnative-matrix} or
     * {@code -Dquarkus.native-matrix.build=true}. Explicit values of these two properties win.
     */
    @WithDefault("false")
    boolean build();

    /**
     * How targets are built by default: {@code sources} runs only {@code native-image} on the builders, {@code full}
     * builds the whole project there. Targets can override it.
     */
    @WithDefault("sources")
    Mode mode();

    /**
     * What to do when a {@code sources} target would receive jars selected for the build machine platform (JavaFX,
     * LWJGL, Netty native transports... picked by OS-activated Maven profiles or platform classifiers).
     */
    @WithDefault("fail")
    Check platformDependencyCheck();

    /**
     * Jar file names (globs with {@code *}) ignored by the platform dependency check, e.g.
     * {@code *netty-transport-native-epoll*}.
     */
    Optional<List<String>> ignoredPlatformDependencies();

    /**
     * What to do when a builder runs another GraalVM {@code major.minor} version than the one the native sources were
     * generated for.
     */
    @WithDefault("warn")
    Check graalvmVersionCheck();

    /**
     * Reuse the executables of the previous matrix build for the targets whose inputs (native sources, platform,
     * runner, mode, options) did not change and whose executable is still in the output directory. Combined with a
     * build that does not {@code clean}, only the failed or changed targets are rebuilt.
     */
    @WithDefault("true")
    boolean reuseUnchanged();

    /**
     * Configuration of {@code mode=full}.
     */
    FullBuildConfig fullBuild();

    /**
     * Packaging of the executables for distribution.
     */
    PackageConfig packaging();

    enum Mode {
        /** Run only {@code native-image} on the builders. */
        SOURCES,
        /** Build the whole project on the builders. */
        FULL
    }

    enum Check {
        /** Fail the target. */
        FAIL,
        /** Log a warning. */
        WARN,
        /** Do not check. */
        OFF
    }

    interface FullBuildConfig {

        /**
         * Root of the project shipped to the builders. Defaults to the directory holding {@code settings.gradle}, else
         * the top-most directory of the chain of {@code pom.xml} files above the Quarkus module.
         */
        Optional<String> projectDirectory();

        /**
         * Build tool of the project; detected from the project root by default.
         */
        Optional<BuildTool> buildTool();

        /**
         * Extra arguments of the build command, e.g. {@code -Pproduction,-Dmy.property=value}.
         */
        Optional<List<String>> args();

        /**
         * Whether to skip the tests in the remote builds.
         */
        @WithDefault("true")
        boolean skipTests();

        /**
         * Replaces the build command used on Linux and macOS builders (run with {@code sh} from the project root; it
         * must produce the executable in the build output directory of the module). Requires
         * {@code windows-command} too.
         */
        Optional<String> posixCommand();

        /**
         * Replaces the build command used on Windows builders (run with {@code cmd.exe}).
         */
        Optional<String> windowsCommand();

        /**
         * Directory names never shipped to the builders.
         */
        @WithDefault("target,build,.git,.idea,.vscode,.gradle,node_modules,.quarkus")
        List<String> excludedDirectories();

        enum BuildTool {
            /** Maven ({@code mvnw} when present). */
            MAVEN,
            /** Gradle ({@code gradlew} when present). */
            GRADLE
        }
    }

    interface PackageConfig {

        /**
         * Also produce one archive per target in {@code <output-directory>/dist}: a {@code .tar.gz} keeping the
         * executable permission for Linux and macOS, a {@code .zip} for Windows. Archives are named
         * {@code <name>-<platform>} with JReleaser platform names ({@code linux-x86_64}, {@code osx-aarch_64},
         * {@code windows-x86_64}...).
         */
        @WithDefault("false")
        boolean archives();

        /**
         * Also write {@code <output-directory>/jreleaser.yml}, a JReleaser configuration declaring the archives as a
         * {@code BINARY} distribution (implies {@code archives}).
         */
        @WithDefault("false")
        boolean jreleaser();

        /**
         * Distribution name used in archive names and in the JReleaser configuration. Defaults to the executable name
         * without the {@code -runner} suffix.
         */
        Optional<String> name();
    }

    interface TargetConfig {

        /**
         * How this target is built; defaults to {@code quarkus.native-matrix.mode}.
         */
        Optional<Mode> mode();

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
