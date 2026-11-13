package io.quarkiverse.nativematrix.deployment.orchestrator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.quarkiverse.nativematrix.spi.BuildContext;
import io.quarkiverse.nativematrix.spi.BuildLog;
import io.quarkiverse.nativematrix.spi.NativeBuildException;
import io.quarkiverse.nativematrix.spi.NativeBuildRequest;
import io.quarkiverse.nativematrix.spi.NativeBuildResult;
import io.quarkiverse.nativematrix.spi.NativeBuildRunner;
import io.quarkiverse.nativematrix.spi.support.FileTrees;

/**
 * Runs every target of the matrix, in parallel, and collects the results.
 * <p>
 * This class does not depend on the Quarkus build API, so it can be tested (and reused) on its own.
 */
public final class NativeMatrixOrchestrator {

    public static final String MANIFEST_FILE = "native-matrix.json";
    private static final Logger LOG = Logger.getLogger("io.quarkiverse.nativematrix");

    /**
     * Settings of a matrix run.
     *
     * @param outputDirectory receives one sub-directory per target and the manifest
     * @param parallelism maximum number of targets built concurrently
     * @param failFast cancel the other targets when one fails
     * @param keepWorkDirectories do not delete the per-target work directories
     * @param quiet log tool output at DEBUG level
     */
    public record Settings(Path outputDirectory, int parallelism, boolean failFast, boolean keepWorkDirectories,
            boolean quiet) {
    }

    /**
     * The outcome of a matrix run.
     */
    public record MatrixResult(String buildId, List<TargetResult> targets, Path manifest) {
        public boolean succeeded() {
            return targets.stream().allMatch(TargetResult::succeeded);
        }
    }

    private final RunnerRegistry registry;
    private final Settings settings;

    public NativeMatrixOrchestrator(RunnerRegistry registry, Settings settings) {
        this.registry = registry;
        this.settings = settings;
    }

    public MatrixResult run(NativeSources sources, List<MatrixTarget> targets) throws IOException {
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("No native matrix target to build");
        }
        String buildId = newBuildId();
        Path outputRoot = settings.outputDirectory().toAbsolutePath();
        Files.createDirectories(outputRoot);

        // 1. resolve and validate everything before starting any (possibly long and costly) build
        Map<MatrixTarget, NativeBuildRunner> runners = new LinkedHashMap<>();
        Map<MatrixTarget, NativeBuildRequest> requests = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (MatrixTarget target : targets) {
            NativeBuildRunner runner = registry.get(target.runnerId()).orElse(null);
            if (runner == null) {
                problems.add(target.name() + ": unknown runner '" + target.runnerId() + "', available runners: "
                        + String.join(", ", registry.ids()));
                continue;
            }
            if (!runner.supports(target.platform())) {
                problems.add(target.name() + ": the '" + runner.id() + "' runner cannot build " + target.platform()
                        + " executables");
                continue;
            }
            BuildLog log = new PrefixedBuildLog(LOG, target.name(), settings.quiet());
            List<String> args = ArgsAdjuster.adjust(sources.args(), target.platform(), sources.directory(), log::warn);
            NativeBuildRequest request = NativeBuildRequest.builder()
                    .buildId(buildId)
                    .targetName(target.name())
                    .platform(target.platform())
                    .nativeSourcesDirectory(sources.directory().toAbsolutePath())
                    .outputName(sources.outputName())
                    .nativeImageArgs(args)
                    .graalVmVersion(sources.graalVmVersion().orElse(null))
                    .builderImage(sources.builderImage().orElse(null))
                    .options(target.options())
                    .outputDirectory(outputRoot.resolve(sanitize(target.name())))
                    .timeout(target.timeout())
                    .build();
            try {
                runner.validate(request);
            } catch (RuntimeException e) {
                problems.add(target.name() + " (" + runner.id() + "): " + e.getMessage());
                continue;
            }
            runners.put(target, runner);
            requests.put(target, request);
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid native matrix configuration:\n  - " + String.join("\n  - ", problems));
        }

        // 2. build
        LOG.infof("Building %d native executable(s) [build id %s]: %s", targets.size(), buildId,
                String.join(", ", targets.stream().map(t -> t.name() + " (" + t.runnerId() + ")").toList()));
        int parallelism = Math.max(1, Math.min(settings.parallelism(), targets.size()));
        AtomicInteger threadIds = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "native-matrix-" + threadIds.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "native-matrix-watchdog");
            t.setDaemon(true);
            return t;
        });
        AtomicBoolean abortAll = new AtomicBoolean();
        List<TargetTask> tasks = new ArrayList<>();
        try {
            for (MatrixTarget target : targets) {
                TargetTask task = new TargetTask(target, runners.get(target), requests.get(target), outputRoot, watchdog,
                        abortAll, tasks);
                tasks.add(task);
            }
            List<Future<TargetResult>> futures = new ArrayList<>();
            for (TargetTask task : tasks) {
                futures.add(pool.submit(task::call));
            }
            List<TargetResult> results = new ArrayList<>();
            for (Future<TargetResult> f : futures) {
                try {
                    results.add(f.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    tasks.forEach(TargetTask::cancel);
                    throw new IOException("Interrupted while waiting for the native builds", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }
            Path manifest = writeManifest(buildId, sources, results, outputRoot);
            logSummary(results, outputRoot);
            return new MatrixResult(buildId, results, manifest);
        } finally {
            pool.shutdownNow();
            watchdog.shutdownNow();
        }
    }

    private final class TargetTask {
        private final MatrixTarget target;
        private final NativeBuildRunner runner;
        private final NativeBuildRequest request;
        private final Path outputRoot;
        private final ScheduledExecutorService watchdog;
        private final AtomicBoolean abortAll;
        private final List<TargetTask> siblings;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean timedOut = new AtomicBoolean();
        private volatile Thread thread;

        TargetTask(MatrixTarget target, NativeBuildRunner runner, NativeBuildRequest request, Path outputRoot,
                ScheduledExecutorService watchdog, AtomicBoolean abortAll, List<TargetTask> siblings) {
            this.target = target;
            this.runner = runner;
            this.request = request;
            this.outputRoot = outputRoot;
            this.watchdog = watchdog;
            this.abortAll = abortAll;
            this.siblings = siblings;
        }

        void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                Thread t = thread;
                if (t != null) {
                    t.interrupt();
                }
            }
        }

        TargetResult call() {
            Instant start = Instant.now();
            BuildLog log = new PrefixedBuildLog(LOG, target.name(), settings.quiet());
            if (abortAll.get() || cancelled.get()) {
                return TargetResult.failure(target, TargetResult.Status.CANCELLED, Duration.ZERO,
                        "Cancelled because another target failed");
            }
            thread = Thread.currentThread();
            Path workDir = outputRoot.resolve(".work").resolve(sanitize(target.name()));
            ScheduledFuture<?> timeout = watchdog.schedule(() -> {
                timedOut.set(true);
                cancel();
            }, target.timeout().toMillis(), TimeUnit.MILLISECONDS);
            try {
                FileTrees.delete(request.outputDirectory());
                Files.createDirectories(request.outputDirectory());
                FileTrees.delete(workDir);
                Files.createDirectories(workDir);
                log.infof("Building %s with the %s runner", target.platform(), runner.description());
                NativeBuildResult result = runner.build(request, new Context(log, workDir));
                Path executable = result.executable().toAbsolutePath().normalize();
                if (!Files.isRegularFile(executable)) {
                    throw new NativeBuildException("The runner reported " + executable + " but the file does not exist");
                }
                if (!executable.startsWith(request.outputDirectory().toAbsolutePath().normalize())) {
                    throw new NativeBuildException("The runner put the executable outside of the output directory: "
                            + executable);
                }
                Duration duration = Duration.between(start, Instant.now());
                log.infof("Built %s in %s", outputRoot.relativize(executable), format(duration));
                return TargetResult.success(target, duration, result, sha256(executable));
            } catch (InterruptedException e) {
                Duration duration = Duration.between(start, Instant.now());
                if (timedOut.get()) {
                    log.error("Timed out after " + format(target.timeout()));
                    return TargetResult.failure(target, TargetResult.Status.TIMED_OUT, duration,
                            "Timed out after " + format(target.timeout()));
                }
                log.warn("Cancelled");
                return TargetResult.failure(target, TargetResult.Status.CANCELLED, duration, "Cancelled");
            } catch (Throwable e) {
                Duration duration = Duration.between(start, Instant.now());
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                if (cancelled.get() && !(e instanceof NativeBuildException)) {
                    // interrupted I/O often surfaces as an IOException or a runtime exception
                    return TargetResult.failure(target,
                            timedOut.get() ? TargetResult.Status.TIMED_OUT : TargetResult.Status.CANCELLED, duration,
                            timedOut.get() ? "Timed out after " + format(target.timeout()) : "Cancelled");
                }
                log.error("Failed: " + message);
                LOG.debugf(e, "[%s] Failure details", target.name());
                if (settings.failFast() && abortAll.compareAndSet(false, true)) {
                    LOG.warnf("Cancelling the other targets (fail-fast)");
                    siblings.stream().filter(t -> t != this).forEach(TargetTask::cancel);
                }
                return TargetResult.failure(target, TargetResult.Status.FAILED, duration, message);
            } finally {
                timeout.cancel(false);
                thread = null;
                Thread.interrupted(); // clear the flag before returning the thread to the pool
                if (!settings.keepWorkDirectories()) {
                    FileTrees.deleteQuietly(workDir);
                }
            }
        }

        private final class Context implements BuildContext {
            private final BuildLog log;
            private final Path workDir;

            Context(BuildLog log, Path workDir) {
                this.log = log;
                this.workDir = workDir;
            }

            @Override
            public BuildLog log() {
                return log;
            }

            @Override
            public Path workDirectory() {
                return workDir;
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        }
    }

    private Path writeManifest(String buildId, NativeSources sources, List<TargetResult> results, Path outputRoot)
            throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("buildId", buildId);
        manifest.put("outputName", sources.outputName());
        sources.graalVmVersion().ifPresent(v -> manifest.put("graalvmVersion", v));
        List<Map<String, Object>> entries = new ArrayList<>();
        for (TargetResult r : results) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", r.target().name());
            e.put("platform", r.target().platform().id());
            e.put("runner", r.target().runnerId());
            e.put("status", r.status().name().toLowerCase(Locale.ROOT));
            e.put("durationSeconds", r.duration().toSeconds());
            r.result().ifPresent(res -> {
                e.put("executable", outputRoot.relativize(res.executable().toAbsolutePath()).toString().replace('\\', '/'));
                if (!res.additionalFiles().isEmpty()) {
                    e.put("additionalFiles", res.additionalFiles().stream()
                            .map(p -> outputRoot.relativize(p.toAbsolutePath()).toString().replace('\\', '/')).toList());
                }
                if (!res.metadata().isEmpty()) {
                    e.put("metadata", res.metadata());
                }
            });
            r.sha256().ifPresent(s -> e.put("sha256", s));
            r.error().ifPresent(s -> e.put("error", s));
            entries.add(e);
        }
        manifest.put("targets", entries);
        Path file = outputRoot.resolve(MANIFEST_FILE);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(file.toFile(), manifest);
        return file;
    }

    private static void logSummary(List<TargetResult> results, Path outputRoot) {
        StringBuilder sb = new StringBuilder("Native matrix summary:");
        int width = results.stream().mapToInt(r -> r.target().name().length()).max().orElse(10);
        for (TargetResult r : results) {
            sb.append("\n  ")
                    .append(String.format("%-" + width + "s", r.target().name()))
                    .append("  ").append(String.format("%-9s", r.status()))
                    .append("  ").append(String.format("%8s", format(r.duration())))
                    .append("  ");
            if (r.succeeded()) {
                sb.append(outputRoot.relativize(r.result().orElseThrow().executable().toAbsolutePath()));
                r.result().orElseThrow().metadata().forEach((k, v) -> sb.append(" ").append(k).append("=").append(v));
            } else {
                sb.append(r.error().orElse(""));
            }
        }
        sb.append("\n  Output: ").append(outputRoot);
        if (results.stream().allMatch(TargetResult::succeeded)) {
            LOG.info(sb);
        } else {
            LOG.error(sb);
        }
    }

    static String newBuildId() {
        String time = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        byte[] random = new byte[3];
        new SecureRandom().nextBytes(random);
        return time + "-" + HexFormat.of().formatHex(random);
    }

    /**
     * @return a lower case string usable in file, container and branch names
     */
    public static String sanitize(String value) {
        String s = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "target" : s;
    }

    static String format(Duration d) {
        long s = d.toSeconds();
        if (s == 0) {
            return d.toMillis() + "ms";
        }
        if (s < 60) {
            return s + "s";
        }
        if (s < 3600) {
            return (s / 60) + "m" + String.format("%02d", s % 60) + "s";
        }
        return (s / 3600) + "h" + String.format("%02d", (s % 3600) / 60) + "m";
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
