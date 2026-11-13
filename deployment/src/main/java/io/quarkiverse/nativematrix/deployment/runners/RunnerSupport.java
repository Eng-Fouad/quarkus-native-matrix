package io.quarkiverse.nativematrix.deployment.runners;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.quarkiverse.nativematrix.spi.NativeBuildException;
import io.quarkiverse.nativematrix.spi.NativeBuildRequest;
import io.quarkiverse.nativematrix.spi.NativeBuildResult;
import io.quarkiverse.nativematrix.spi.support.FileTrees;

/**
 * Helpers shared by the built-in runners.
 */
final class RunnerSupport {

    private RunnerSupport() {
    }

    /**
     * Builds the result from the files found in the output directory of the request: the executable plus every other
     * file (shared libraries, {@code build-artifacts.json}...).
     */
    static NativeBuildResult collectResult(NativeBuildRequest request) throws IOException, NativeBuildException {
        Path out = request.outputDirectory();
        Path executable = out.resolve(request.executableName());
        if (!Files.isRegularFile(executable)) {
            List<Path> found = FileTrees.listFiles(out);
            throw new NativeBuildException("native-image completed but " + request.executableName()
                    + " was not produced" + (found.isEmpty() ? ""
                            : "; found: " + found.stream()
                                    .map(p -> out.relativize(p).toString()).toList()));
        }
        if (request.platform().os().isPosix()) {
            FileTrees.makeExecutable(executable);
        }
        List<Path> others = new ArrayList<>();
        for (Path p : FileTrees.listFiles(out)) {
            if (!p.equals(executable)) {
                others.add(p);
            }
        }
        return new NativeBuildResult(executable, others, null);
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    static String cmdQuote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
