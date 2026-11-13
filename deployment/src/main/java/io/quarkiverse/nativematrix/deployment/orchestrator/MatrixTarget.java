package io.quarkiverse.nativematrix.deployment.orchestrator;

import java.time.Duration;
import java.util.Objects;

import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.RunnerOptions;

/**
 * One resolved entry of the matrix.
 */
public record MatrixTarget(String name, Platform platform, String runnerId, RunnerOptions options, Duration timeout) {

    public MatrixTarget {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(runnerId, "runnerId");
        options = options == null ? RunnerOptions.empty() : options;
        Objects.requireNonNull(timeout, "timeout");
    }
}
