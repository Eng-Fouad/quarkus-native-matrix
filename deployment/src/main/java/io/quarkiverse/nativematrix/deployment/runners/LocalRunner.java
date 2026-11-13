package io.quarkiverse.nativematrix.deployment.runners;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.quarkiverse.nativematrix.deployment.orchestrator.PathRelocator;
import io.quarkiverse.nativematrix.spi.BuildContext;
import io.quarkiverse.nativematrix.spi.NativeBuildException;
import io.quarkiverse.nativematrix.spi.NativeBuildRequest;
import io.quarkiverse.nativematrix.spi.NativeBuildResult;
import io.quarkiverse.nativematrix.spi.NativeBuildRunner;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.RunnerOptions;
import io.quarkiverse.nativematrix.spi.support.ArgFile;
import io.quarkiverse.nativematrix.spi.support.Processes;

/**
 * Runs {@code native-image} on the build machine. Only builds for the platform of the build machine.
 * <p>
 * Options:
 * <ul>
 * <li>{@code graalvm-home}: GraalVM/Mandrel installation; defaults to {@code GRAALVM_HOME}, then {@code JAVA_HOME},
 * then the {@code PATH}</li>
 * <li>{@code command}: comma separated command replacing {@code native-image} (a wrapper script...). The argument file
 * is appended as {@code @file}.</li>
 * </ul>
 */
public class LocalRunner implements NativeBuildRunner {

    public static final String ID = "local";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "local (" + Platform.host() + ")";
    }

    @Override
    public boolean supports(Platform platform) {
        return platform.equals(Platform.host());
    }

    @Override
    public void validate(NativeBuildRequest request) {
        if (request.options().getList("command").isEmpty() && findNativeImage(request.options()).isEmpty()) {
            throw new IllegalArgumentException("Cannot find native-image: set the graalvm-home option, GRAALVM_HOME,"
                    + " JAVA_HOME or add native-image to the PATH");
        }
    }

    @Override
    public NativeBuildResult build(NativeBuildRequest request, BuildContext context) throws Exception {
        List<String> command = new ArrayList<>(request.options().getList("command"));
        if (command.isEmpty()) {
            command.add(findNativeImage(request.options())
                    .orElseThrow(() -> new NativeBuildException("Cannot find native-image")).toString());
        }
        List<String> versionCommand = new ArrayList<>(command);
        versionCommand.add("--version");
        Processes.Result version = Processes.capture(versionCommand, context.workDirectory());
        if (version.succeeded()) {
            context.reportNativeImageVersion(version.outputAsString());
        } else {
            context.log().warn("native-image --version failed: " + version.outputAsString());
        }

        // Run in the private work directory so that parallel targets never write into the shared native sources;
        // the jar is referenced by absolute path and the classpath is resolved from its manifest.
        Path sources = request.nativeSourcesDirectory();
        String output = request.outputDirectory().resolve(request.outputName()).toAbsolutePath().toString()
                .replace('\\', '/');
        List<String> args = withAbsoluteJar(request.nativeImageArgs(output), sources);
        Path argFile = ArgFile.write(context.workDirectory().resolve(ArgFile.DEFAULT_NAME), args);
        command.add("@" + argFile.toAbsolutePath());

        Processes.Result result = Processes.run(command, context.workDirectory(), java.util.Map.of(), context.log());
        if (!result.succeeded()) {
            throw new NativeBuildException("native-image failed with exit code " + result.exitCode());
        }
        return RunnerSupport.collectResult(request);
    }

    /**
     * The build runs in the work directory: paths relative to the native sources (the runner jar, the files shipped by
     * {@link PathRelocator}) are made absolute.
     */
    static List<String> withAbsoluteJar(List<String> args, Path sources) {
        List<String> copy = new ArrayList<>(args);
        int jar = copy.lastIndexOf("-jar");
        if (jar >= 0 && jar + 1 < copy.size()) {
            copy.set(jar + 1, absolute(sources, copy.get(jar + 1)));
        }
        String extra = PathRelocator.EXTRA_DIRECTORY + "/";
        for (int i = 0; i < copy.size(); i++) {
            String arg = copy.get(i);
            if (!arg.contains(extra)) {
                continue;
            }
            int eq = arg.indexOf('=');
            String prefix = eq >= 0 ? arg.substring(0, eq + 1) : "";
            String[] parts = (eq >= 0 ? arg.substring(eq + 1) : arg).split(",", -1);
            for (int p = 0; p < parts.length; p++) {
                if (parts[p].startsWith(extra)) {
                    parts[p] = absolute(sources, parts[p]);
                }
            }
            copy.set(i, prefix + String.join(",", parts));
        }
        return copy;
    }

    private static String absolute(Path sources, String relative) {
        return sources.resolve(relative).toAbsolutePath().toString().replace('\\', '/');
    }

    static Optional<Path> findNativeImage(RunnerOptions options) {
        List<Path> dirs = new ArrayList<>();
        options.get("graalvm-home").ifPresent(h -> dirs.add(Path.of(h, "bin")));
        for (String env : new String[] { "GRAALVM_HOME", "JAVA_HOME" }) {
            String value = System.getenv(env);
            if (value != null && !value.isBlank()) {
                dirs.add(Path.of(value, "bin"));
            }
        }
        return Processes.findExecutable("native-image", dirs);
    }
}
