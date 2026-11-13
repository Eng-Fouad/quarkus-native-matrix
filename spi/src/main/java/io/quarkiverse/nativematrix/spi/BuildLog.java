package io.quarkiverse.nativematrix.spi;

/**
 * Logger handed to runners. Implementations prefix every line with the target name, so that the interleaved output of
 * parallel builds stays readable.
 */
public interface BuildLog {

    void debug(String message);

    void info(String message);

    void warn(String message);

    void error(String message);

    /**
     * A line of output produced by {@code native-image} or another tool. Logged at INFO level by default; shown only
     * at DEBUG level when {@code quarkus.native-matrix.quiet} is enabled.
     */
    void output(String line);

    default void infof(String format, Object... args) {
        info(String.format(format, args));
    }

    default void warnf(String format, Object... args) {
        warn(String.format(format, args));
    }

    default void debugf(String format, Object... args) {
        debug(String.format(format, args));
    }

    /**
     * A log that writes to standard output, useful when testing runners outside of a Quarkus build.
     */
    static BuildLog console(String prefix) {
        return new BuildLog() {
            private void print(String level, String message) {
                System.out.println("[" + prefix + "] " + level + " " + message);
            }

            @Override
            public void debug(String message) {
                print("DEBUG", message);
            }

            @Override
            public void info(String message) {
                print("INFO ", message);
            }

            @Override
            public void warn(String message) {
                print("WARN ", message);
            }

            @Override
            public void error(String message) {
                print("ERROR", message);
            }

            @Override
            public void output(String line) {
                System.out.println("[" + prefix + "] | " + line);
            }
        };
    }
}
