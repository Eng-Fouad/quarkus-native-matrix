package io.quarkiverse.nativematrix.deployment.orchestrator;

import java.time.Duration;
import java.util.Optional;

import io.quarkiverse.nativematrix.spi.NativeBuildResult;

/**
 * The outcome of one target.
 */
public record TargetResult(MatrixTarget target, Status status, Duration duration, Optional<NativeBuildResult> result,
        Optional<String> sha256, Optional<String> error) {

    public enum Status {
        SUCCESS,
        FAILED,
        TIMED_OUT,
        CANCELLED
    }

    public boolean succeeded() {
        return status == Status.SUCCESS;
    }

    static TargetResult success(MatrixTarget target, Duration duration, NativeBuildResult result, String sha256) {
        return new TargetResult(target, Status.SUCCESS, duration, Optional.of(result), Optional.of(sha256),
                Optional.empty());
    }

    static TargetResult failure(MatrixTarget target, Status status, Duration duration, String error) {
        return new TargetResult(target, status, duration, Optional.empty(), Optional.empty(), Optional.of(error));
    }
}
