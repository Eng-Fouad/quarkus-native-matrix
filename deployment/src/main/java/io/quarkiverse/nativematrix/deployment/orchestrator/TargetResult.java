package io.quarkiverse.nativematrix.deployment.orchestrator;

import java.time.Duration;
import java.util.Optional;

import io.quarkiverse.nativematrix.spi.NativeBuildResult;

/**
 * The outcome of one target.
 *
 * @param fingerprint hash of the inputs of the target, used to reuse unchanged executables (empty when not computed)
 */
public record TargetResult(MatrixTarget target, Status status, Duration duration, Optional<NativeBuildResult> result,
        Optional<String> sha256, Optional<String> error, Optional<String> fingerprint) {

    public enum Status {
        SUCCESS,
        /** The executable of the previous run was reused: the inputs did not change. */
        REUSED,
        FAILED,
        TIMED_OUT,
        CANCELLED
    }

    public boolean succeeded() {
        return status == Status.SUCCESS || status == Status.REUSED;
    }

    static TargetResult success(MatrixTarget target, Duration duration, NativeBuildResult result, String sha256,
            String fingerprint) {
        return new TargetResult(target, Status.SUCCESS, duration, Optional.of(result), Optional.of(sha256),
                Optional.empty(), Optional.ofNullable(fingerprint));
    }

    static TargetResult reused(MatrixTarget target, NativeBuildResult result, String sha256, String fingerprint) {
        return new TargetResult(target, Status.REUSED, Duration.ZERO, Optional.of(result), Optional.of(sha256),
                Optional.empty(), Optional.of(fingerprint));
    }

    static TargetResult failure(MatrixTarget target, Status status, Duration duration, String error) {
        return new TargetResult(target, status, duration, Optional.empty(), Optional.empty(), Optional.of(error),
                Optional.empty());
    }
}
