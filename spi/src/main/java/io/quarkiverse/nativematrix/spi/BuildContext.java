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
     * @throws InterruptedException if the build was cancelled
     */
    default void checkCancelled() throws InterruptedException {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Native build cancelled");
        }
    }
}
