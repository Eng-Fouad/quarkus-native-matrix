package io.quarkiverse.nativematrix.deployment.orchestrator;

import java.time.Duration;
import java.util.Objects;

import io.quarkiverse.nativematrix.spi.BuildMode;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.RunnerOptions;

/**
 * One resolved entry of the matrix.
 */
public record MatrixTarget(String name, Platform platform, String runnerId, RunnerOptions options, Duration timeout,
        BuildMode mode) {

    public MatrixTarget {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(runnerId, "runnerId");
        options = options == null ? RunnerOptions.empty() : options;
        Objects.requireNonNull(timeout, "timeout");
        mode = mode == null ? BuildMode.SOURCES : mode;
    }

    /**
     * A target built in {@link BuildMode#SOURCES} mode.
     */
    public MatrixTarget(String name, Platform platform, String runnerId, RunnerOptions options, Duration timeout) {
        this(name, platform, runnerId, options, timeout, BuildMode.SOURCES);
    }
}
