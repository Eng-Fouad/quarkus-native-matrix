package io.quarkiverse.nativematrix.deployment.orchestrator;

/**
 * What to do when a consistency check finds a problem.
 */
public enum CheckPolicy {
    /** Fail the target (before it starts building when possible). */
    FAIL,
    /** Log a warning and continue. */
    WARN,
    /** Do not check. */
    OFF
}
