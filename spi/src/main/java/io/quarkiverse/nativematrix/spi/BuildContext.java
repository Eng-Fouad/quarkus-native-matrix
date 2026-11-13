package io.quarkiverse.nativematrix.spi;

import java.nio.file.Path;

/**
 * Services the orchestrator provides to a running {@link NativeBuildRunner}.
 */
public interface BuildContext {

    /**
     * @return a logger whose messages are prefixed with the target name
     */
    BuildLog log();

    /**
     * @return a private, empty, local scratch directory for this target. It is deleted after the build unless
     *         {@code quarkus.native-matrix.keep-work-directories} is set.
     */
    Path workDirectory();

    /**
     * @return {@code true} if the build was cancelled (timeout, or fail-fast after another target failed)
     */
    boolean isCancelled();

    /**
     * Reports the output of {@code native-image --version} on the builder. The orchestrator compares it with the
     * GraalVM version the native sources were generated for and, depending on
     * {@code quarkus.native-matrix.graalvm-version-check}, logs a warning or fails the target; the detected version is
     * also recorded in the manifest. Runners should call it before starting {@code native-image} when they can, or
     * after the build otherwise.
     *
     * @throws NativeBuildException if the versions do not match and the check is configured to fail
     */
    default void reportNativeImageVersion(String versionOutput) throws NativeBuildException {
    }

    /**
     * @throws InterruptedException if the build was cancelled
     */
    default void checkCancelled() throws InterruptedException {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Native build cancelled");
        }
    }
}
