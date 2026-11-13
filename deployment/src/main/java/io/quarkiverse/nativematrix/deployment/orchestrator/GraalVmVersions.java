package io.quarkiverse.nativematrix.deployment.orchestrator;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.quarkus.deployment.pkg.steps.GraalVM;

/**
 * Compares the GraalVM version of a builder with the version the native sources were generated for.
 * <p>
 * Quarkus generates {@code native-image} arguments for a given GraalVM release ({@code native-sources/graalvm.version});
 * options change between feature releases, so builders should run the same {@code major.minor}.
 */
public final class GraalVmVersions {

    private static final Pattern MAJOR_MINOR = Pattern.compile("(\\d+)\\.(\\d+)");

    private GraalVmVersions() {
    }

    /**
     * @return the {@code major.minor} GraalVM version from the output of {@code native-image --version}, parsed with
     *         the same logic as Quarkus (handling GraalVM CE/EE, Oracle GraalVM, Mandrel and Liberica)
     */
    public static Optional<String> parse(String versionOutput) {
        try {
            return Optional.of(GraalVM.Version.of(versionOutput.lines()).getMajorMinorAsString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * @return the {@code major.minor} part of a version such as {@code 25.0.4}
     */
    public static Optional<String> majorMinor(String version) {
        Matcher m = MAJOR_MINOR.matcher(version);
        return m.find() ? Optional.of(m.group(1) + "." + m.group(2)) : Optional.empty();
    }
}
