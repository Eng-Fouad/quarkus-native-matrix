package io.quarkiverse.nativematrix.deployment.orchestrator;

import org.jboss.logging.Logger;

import io.quarkiverse.nativematrix.spi.BuildLog;

/**
 * A {@link BuildLog} backed by JBoss Logging, prefixing every message with the target name.
 */
final class PrefixedBuildLog implements BuildLog {

    private final Logger log;
    private final String prefix;
    private final boolean quiet;

    PrefixedBuildLog(Logger log, String target, boolean quiet) {
        this.log = log;
        this.prefix = "[" + target + "] ";
        this.quiet = quiet;
    }

    @Override
    public void debug(String message) {
        log.debug(prefix + message);
    }

    @Override
    public void info(String message) {
        log.info(prefix + message);
    }

    @Override
    public void warn(String message) {
        log.warn(prefix + message);
    }

    @Override
    public void error(String message) {
        log.error(prefix + message);
    }

    @Override
    public void output(String line) {
        if (quiet) {
            log.debug(prefix + "| " + line);
        } else {
            log.info(prefix + "| " + line);
        }
    }
}
