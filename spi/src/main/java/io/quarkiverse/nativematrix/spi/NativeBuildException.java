package io.quarkiverse.nativematrix.spi;

/**
 * A native build failed for a reason the user can act on. The message is shown as is in the build summary, so it
 * should say what went wrong and, when possible, how to fix it.
 */
public class NativeBuildException extends Exception {

    private static final long serialVersionUID = 1L;

    public NativeBuildException(String message) {
        super(message);
    }

    public NativeBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
