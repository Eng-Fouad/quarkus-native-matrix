package io.quarkiverse.nativematrix.deployment.runners;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.quarkiverse.nativematrix.deployment.orchestrator.NativeMatrixOrchestrator;
import io.quarkiverse.nativematrix.spi.BuildContext;
import io.quarkiverse.nativematrix.spi.BuildLog;
import io.quarkiverse.nativematrix.spi.NativeBuildException;
import io.quarkiverse.nativematrix.spi.NativeBuildRequest;
import io.quarkiverse.nativematrix.spi.NativeBuildResult;
import io.quarkiverse.nativematrix.spi.NativeBuildRunner;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.RunnerOptions;
import io.quarkiverse.nativematrix.spi.support.ArgFile;
import io.quarkiverse.nativematrix.spi.support.Processes;

/**
 * Runs {@code native-image} in a builder container, on a local or remote Docker/Podman engine. Linux targets only.
 * <p>
 * Files are transferred with {@code docker cp} rather than bind mounts, so the engine can be remote: point
 * {@code context} (or {@code host}) at an arm64 machine to get native-speed {@code linux-arm64} builds. With a local
 * amd64 engine, {@code linux-arm64} builds run under QEMU emulation (if installed), which works but is very slow.
 * <p>
 * Options:
 * <ul>
 * <li>{@code runtime}: {@code docker} (default) or {@code podman}, or the path of the executable</li>
 * <li>{@code context}: Docker context ({@code docker --context}) or Podman connection ({@code podman --connection})</li>
 * <li>{@code host}: engine URL ({@code docker -H} / {@code podman --url}), e.g. {@code ssh://builder@arm-box}</li>
 * <li>{@code image}: builder image; defaults to {@code quarkus.native.builder-image} when Quarkus recorded it, else
 * {@value #DEFAULT_IMAGE}</li>
 * <li>{@code pull}: {@code missing} (default), {@code always} or {@code never}</li>
 * <li>{@code platform}: container platform; defaults to the target platform, e.g. {@code linux/arm64}</li>
 * <li>{@code entrypoint}: defaults to {@code native-image}</li>
 * <li>{@code user}: user the build runs as, e.g. {@code root} if the image user cannot write to {@code /project}</li>
 * <li>{@code memory}, {@code cpus}: resource limits</li>
 * <li>{@code create-args}: comma separated extra arguments of {@code docker create}</li>
 * </ul>
 */
public class DockerRunner implements NativeBuildRunner {

    public static final String ID = "docker";
    public static final String DEFAULT_IMAGE = "quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25";
    static final String PROJECT_DIR = "/project";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "docker (container builder)";
    }

    @Override
    public boolean supports(Platform platform) {
        return platform.os() == Platform.Os.LINUX;
    }

    @Override
    public void validate(NativeBuildRequest request) {
        String runtime = runtime(request.options());
        if (Processes.findExecutable(runtime, List.of()).isEmpty() && !Path.of(runtime).isAbsolute()) {
            throw new IllegalArgumentException("Container runtime '" + runtime + "' not found in the PATH");
        }
        String pull = request.options().get("pull", "missing");
        if (!List.of("missing", "always", "never").contains(pull)) {
            throw new IllegalArgumentException("Option 'pull' must be missing, always or never, got '" + pull + "'");
        }
    }

    @Override
    public NativeBuildResult build(NativeBuildRequest request, BuildContext context) throws Exception {
        RunnerOptions options = request.options();
        BuildLog log = context.log();
        String name = "native-matrix-" + request.buildId() + "-" + NativeMatrixOrchestrator.sanitize(request.targetName());
        List<String> base = baseCommand(options);
        String image = image(request);

        Path argFile = ArgFile.write(context.workDirectory().resolve(ArgFile.DEFAULT_NAME), request.nativeImageArgs());
        boolean created = false;
        try {
            if (options.get("pull", "missing").equals("always")) {
                log.info("Pulling " + image);
                run(concat(base, "pull", "--platform", platform(request), image), log, "pull " + image);
            }
            log.info("Creating builder container from " + image);
            run(createCommand(base, name, request, image), log, "create the builder container");
            created = true;

            log.info("Copying the native sources into the container");
            run(concat(base, "cp", request.nativeSourcesDirectory().toAbsolutePath() + "/.", name + ":" + PROJECT_DIR),
                    log, "copy the native sources");
            run(concat(base, "cp", argFile.toAbsolutePath().toString(), name + ":" + PROJECT_DIR + "/" + ArgFile.DEFAULT_NAME),
                    log, "copy the argument file");

            Processes.Result result = Processes.run(concat(base, "start", "--attach", name), null, Map.of(), log);
            if (!result.succeeded()) {
                throw new NativeBuildException("native-image failed in the builder container (exit code "
                        + result.exitCode() + ")");
            }

            run(concat(base, "cp", name + ":" + PROJECT_DIR + "/" + NativeBuildRequest.DEFAULT_OUTPUT_DIR + "/.",
                    request.outputDirectory().toAbsolutePath().toString()), log, "copy the executable back");
            return RunnerSupport.collectResult(request).withMetadata("image", image);
        } finally {
            if (created) {
                cleanup(base, name, log);
            }
        }
    }

    List<String> createCommand(List<String> base, String name, NativeBuildRequest request, String image) {
        RunnerOptions options = request.options();
        List<String> cmd = new ArrayList<>(base);
        cmd.addAll(List.of("create", "--name", name,
                "--platform", platform(request),
                "--pull", options.get("pull", "missing"),
                "-v", name + ":" + PROJECT_DIR,
                "-w", PROJECT_DIR,
                "--entrypoint", options.get("entrypoint", "native-image")));
        options.get("user").ifPresent(u -> cmd.addAll(List.of("--user", u)));
        options.get("memory").ifPresent(m -> cmd.addAll(List.of("--memory", m)));
        options.get("cpus").ifPresent(c -> cmd.addAll(List.of("--cpus", c)));
        cmd.addAll(options.getList("create-args"));
        cmd.add(image);
        cmd.add("@" + ArgFile.DEFAULT_NAME);
        return cmd;
    }

    static List<String> baseCommand(RunnerOptions options) {
        String runtime = runtime(options);
        boolean podman = Path.of(runtime).getFileName().toString().startsWith("podman");
        List<String> base = new ArrayList<>();
        base.add(runtime);
        options.get("context").ifPresent(c -> base.addAll(List.of(podman ? "--connection" : "--context", c)));
        options.get("host").ifPresent(h -> base.addAll(List.of(podman ? "--url" : "-H", h)));
        return base;
    }

    private static String runtime(RunnerOptions options) {
        return options.get("runtime", "docker");
    }

    private static String image(NativeBuildRequest request) {
        return request.options().get("image").or(request::builderImage).orElse(DEFAULT_IMAGE);
    }

    private static String platform(NativeBuildRequest request) {
        return request.options().get("platform", request.platform().containerPlatform());
    }

    private static void run(List<String> command, BuildLog log, String what) throws Exception {
        Processes.Result r = Processes.run(command, null, Map.of(), log);
        if (!r.succeeded()) {
            throw new NativeBuildException("Failed to " + what + " (exit code " + r.exitCode() + "): "
                    + String.join(" ", command));
        }
    }

    private static void cleanup(List<String> base, String name, BuildLog log) {
        // must run even when the build thread was interrupted
        boolean interrupted = Thread.interrupted();
        try {
            Processes.capture(concat(base, "rm", "--force", "--volumes", name), null);
            Processes.capture(concat(base, "volume", "rm", "--force", name), null);
        } catch (Exception e) {
            log.warn("Could not remove container/volume " + name + ": " + e.getMessage());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static List<String> concat(List<String> base, String... args) {
        List<String> cmd = new ArrayList<>(base);
        cmd.addAll(List.of(args));
        return cmd;
    }
}
