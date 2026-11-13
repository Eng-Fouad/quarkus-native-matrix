package io.quarkiverse.nativematrix.spi;

/**
 * Executes {@code native-image} for one target platform, somewhere.
 * <p>
 * The Quarkus build produces the platform independent <em>native sources</em> (the runner jar, its {@code lib/}
 * directory and the {@code native-image} arguments) once. For every configured target, the orchestrator hands a
 * {@link NativeBuildRequest} to the runner selected by {@code quarkus.native-matrix.target."<name>".runner}. The runner
 * must get those sources to a machine of the requested {@link Platform}, run {@code native-image} there, and bring the
 * resulting files back into {@link NativeBuildRequest#outputDirectory()}.
 *
 * <h2>Registering a custom runner</h2>
 * Runners are discovered with {@link java.util.ServiceLoader}: list the implementation class in
 * {@code META-INF/services/io.quarkiverse.nativematrix.spi.NativeBuildRunner} of a jar that is on the Quarkus build
 * (deployment) class path, for instance the deployment module of your own extension or a plain dependency of the
 * application. Implementations need a public no-arg constructor and must be thread safe: the same instance is used
 * for every target that selects it, and targets build in parallel.
 *
 * <h2>Configuration</h2>
 * Runner specific settings are passed through {@link NativeBuildRequest#options()}. They are merged from
 * {@code quarkus.native-matrix.runner."<id>".<key>} (defaults for every target using the runner) and
 * {@code quarkus.native-matrix.target."<name>".options.<key>} (per target, wins).
 *
 * <h2>Cancellation</h2>
 * The build runs on a dedicated thread which is interrupted on timeout or when another target fails and
 * {@code fail-fast} is enabled. Long waits must therefore be interruptible, or poll
 * {@link BuildContext#checkCancelled()}; remote work should be cleaned up in {@code finally} blocks.
 */
public interface NativeBuildRunner {

    /**
     * @return the identifier users reference in configuration, e.g. {@code ssh}. Lower case, no spaces.
     */
    String id();

    /**
     * @return a one-line, human readable description, used in logs and error messages
     */
    default String description() {
        return id();
    }

    /**
     * Whether this runner is able to produce executables for the given platform at all. Returning {@code false} makes
     * the build fail fast with a clear message instead of failing in the middle of a remote build.
     */
    default boolean supports(Platform platform) {
        return true;
    }

    /**
     * Whether this runner implements the given build mode. Runners support {@link BuildMode#SOURCES} by default;
     * supporting {@link BuildMode#FULL} means following the contract of {@link FullBuild}.
     */
    default boolean supports(BuildMode mode) {
        return mode == BuildMode.SOURCES;
    }

    /**
     * Validates the request before any target starts building, e.g. checks that required options are present. Every
     * target of the matrix is validated first, so a typo in the last target does not waste an hour of build time.
     *
     * @throws IllegalArgumentException (or any runtime exception) with a user friendly message if the request is invalid
     */
    default void validate(NativeBuildRequest request) {
    }

    /**
     * Builds the native executable. Blocking.
     *
     * @return the produced files, which must be located in {@link NativeBuildRequest#outputDirectory()}
     * @throws InterruptedException if the build was cancelled
     * @throws Exception on any failure; the message is reported to the user, so make it actionable
     */
    NativeBuildResult build(NativeBuildRequest request, BuildContext context) throws Exception;
}
