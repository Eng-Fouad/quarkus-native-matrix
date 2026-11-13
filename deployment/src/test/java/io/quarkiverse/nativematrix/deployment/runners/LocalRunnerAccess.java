package io.quarkiverse.nativematrix.deployment.runners;

import java.nio.file.Path;
import java.util.List;

/**
 * Exposes package-private helpers of the runners to tests of other packages.
 */
public final class LocalRunnerAccess {

    private LocalRunnerAccess() {
    }

    public static List<String> absolutize(List<String> args, Path sources) {
        return LocalRunner.withAbsoluteJar(args, sources);
    }
}
