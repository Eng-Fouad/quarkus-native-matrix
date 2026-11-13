package io.quarkiverse.nativematrix.spi.support;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.quarkiverse.nativematrix.spi.BuildLog;

/**
 * Runs local processes with streamed output and proper cancellation: when the calling thread is interrupted, the
 * process and its descendants are destroyed.
 */
public final class Processes {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private Processes() {
    }

    /**
     * The outcome of a process.
     *
     * @param exitCode the exit code
     * @param output the merged stdout/stderr lines (only when captured)
     */
    public record Result(int exitCode, List<String> output) {
        public boolean succeeded() {
            return exitCode == 0;
        }

        public String outputAsString() {
            return String.join(System.lineSeparator(), output);
        }
    }

    /**
     * Runs {@code command} and streams each output line (stdout and stderr merged) to {@link BuildLog#output(String)}.
     */
    public static Result run(List<String> command, Path workingDirectory, Map<String, String> environment, BuildLog log)
            throws IOException, InterruptedException {
        log.debug("Running: " + String.join(" ", command));
        return execute(command, workingDirectory, environment, log::output, false);
    }

    /**
     * Runs {@code command} and returns its output.
     */
    public static Result capture(List<String> command, Path workingDirectory) throws IOException, InterruptedException {
        return execute(command, workingDirectory, Map.of(), line -> {
        }, true);
    }

    /**
     * Runs {@code command}, returns its trimmed output, and throws if it fails.
     */
    public static String captureOrFail(List<String> command, Path workingDirectory) throws IOException, InterruptedException {
        Result r = capture(command, workingDirectory);
        if (!r.succeeded()) {
            throw new IOException("Command '" + String.join(" ", command) + "' failed with exit code " + r.exitCode()
                    + (r.output().isEmpty() ? "" : ": " + r.outputAsString()));
        }
        return r.outputAsString().trim();
    }

    private static Result execute(List<String> command, Path workingDirectory, Map<String, String> environment,
            Consumer<String> lineConsumer, boolean capture) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (workingDirectory != null) {
            pb.directory(workingDirectory.toFile());
        }
        pb.environment().putAll(environment);
        Process process = pb.start();
        List<String> output = capture ? new ArrayList<>() : List.of();
        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineConsumer.accept(line);
                    if (capture) {
                        synchronized (output) {
                            output.add(line);
                        }
                    }
                }
            } catch (IOException ignored) {
                // process destroyed
            }
        }, "native-matrix-output-" + process.pid());
        pump.setDaemon(true);
        pump.start();
        try {
            int exit = process.waitFor();
            pump.join(TimeUnit.SECONDS.toMillis(10));
            return new Result(exit, capture ? List.copyOf(output) : List.of());
        } catch (InterruptedException e) {
            destroy(process);
            throw e;
        }
    }

    /**
     * Destroys a process and all its descendants.
     */
    public static void destroy(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /**
     * Looks up an executable: first in {@code preferredDirectories}, then in the {@code PATH}. On Windows the
     * {@code PATHEXT} extensions ({@code .exe}, {@code .cmd}...) are tried.
     */
    public static Optional<Path> findExecutable(String name, List<Path> preferredDirectories) {
        List<Path> dirs = new ArrayList<>(preferredDirectories);
        String path = System.getenv("PATH");
        if (path != null) {
            for (String p : path.split(File.pathSeparator)) {
                if (!p.isBlank()) {
                    dirs.add(Path.of(p.trim()));
                }
            }
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(name);
        if (WINDOWS) {
            String pathExt = Optional.ofNullable(System.getenv("PATHEXT")).orElse(".COM;.EXE;.BAT;.CMD");
            for (String ext : pathExt.split(";")) {
                candidates.add(name + ext.toLowerCase(Locale.ROOT));
            }
        }
        for (Path dir : dirs) {
            for (String candidate : candidates) {
                Path p = dir.resolve(candidate);
                if (Files.isRegularFile(p) && (WINDOWS ? !candidate.equals(name) : Files.isExecutable(p))) {
                    return Optional.of(p);
                }
            }
        }
        return Optional.empty();
    }
}
