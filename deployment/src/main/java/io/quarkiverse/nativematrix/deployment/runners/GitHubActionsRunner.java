package io.quarkiverse.nativematrix.deployment.runners;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import io.quarkiverse.nativematrix.deployment.orchestrator.NativeMatrixOrchestrator;
import io.quarkiverse.nativematrix.spi.BuildContext;
import io.quarkiverse.nativematrix.spi.BuildLog;
import io.quarkiverse.nativematrix.spi.NativeBuildException;
import io.quarkiverse.nativematrix.spi.NativeBuildRequest;
import io.quarkiverse.nativematrix.spi.NativeBuildResult;
import io.quarkiverse.nativematrix.spi.NativeBuildRunner;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.RunnerOptions;
import io.quarkiverse.nativematrix.spi.support.Archives;
import io.quarkiverse.nativematrix.spi.support.ArgFile;

/**
 * Builds on GitHub-hosted (or self-hosted) GitHub Actions runners. Gives access to Linux, macOS and Windows on amd64
 * and arm64 without owning any machine.
 * <p>
 * The native sources are uploaded as an asset of a temporary <em>draft</em> release (visible only to collaborators of
 * the repository), the {@code native-matrix.yml} workflow is dispatched, and the executable is downloaded from the
 * artifact of the run. The draft release is deleted afterwards. The workflow must be committed to the repository:
 * when it is missing the build fails and writes it next to the executables.
 * <p>
 * Options:
 * <ul>
 * <li>{@code repository} (required): {@code owner/name}</li>
 * <li>{@code token}: a token with {@code contents:write} and {@code actions:write} on the repository; defaults to the
 * {@code GITHUB_TOKEN} or {@code GH_TOKEN} environment variable</li>
 * <li>{@code workflow}: workflow file name, defaults to {@code native-matrix.yml}</li>
 * <li>{@code ref}: branch holding the workflow, defaults to the default branch</li>
 * <li>{@code runs-on}: runner label; defaults to a GitHub-hosted runner of the target platform</li>
 * <li>{@code java-version}: derived from the GraalVM version recorded by Quarkus, else {@code 25}</li>
 * <li>{@code distribution}: {@code graalvm-community} (default), {@code graalvm}, {@code mandrel}, {@code liberica}</li>
 * <li>{@code api-url}: defaults to {@code https://api.github.com}; for GitHub Enterprise Server use
 * {@code https://HOST/api/v3}. {@code uploads-url} is derived from it.</li>
 * <li>{@code poll-interval} (15s), {@code dispatch-timeout} (3m), {@code keep-release} (false),
 * {@code artifact-retention-days} (1)</li>
 * </ul>
 */
public class GitHubActionsRunner implements NativeBuildRunner {

    public static final String ID = "github-actions";
    public static final String DEFAULT_WORKFLOW = "native-matrix.yml";
    static final String WORKFLOW_RESOURCE = "/io/quarkiverse/nativematrix/github/native-matrix.yml";

    private static final Map<Platform, String> DEFAULT_RUNS_ON = Map.of(
            Platform.parse("linux-amd64"), "ubuntu-24.04",
            Platform.parse("linux-arm64"), "ubuntu-24.04-arm",
            Platform.parse("macos-arm64"), "macos-15",
            Platform.parse("macos-amd64"), "macos-15-intel",
            Platform.parse("windows-amd64"), "windows-2025",
            Platform.parse("windows-arm64"), "windows-11-arm");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "github-actions";
    }

    @Override
    public void validate(NativeBuildRequest request) {
        RunnerOptions o = request.options();
        String repository = o.require("repository");
        if (!repository.matches("[\\w.-]+/[\\w.-]+")) {
            throw new IllegalArgumentException("Option 'repository' must be owner/name, got '" + repository + "'");
        }
        if (token(o).isEmpty()) {
            throw new IllegalArgumentException("No GitHub token: set the 'token' option (e.g. token=${GITHUB_TOKEN})"
                    + " or the GITHUB_TOKEN / GH_TOKEN environment variable");
        }
        o.getDuration("poll-interval", Duration.ofSeconds(15));
        o.getDuration("dispatch-timeout", Duration.ofMinutes(3));
    }

    @Override
    public NativeBuildResult build(NativeBuildRequest request, BuildContext context) throws Exception {
        RunnerOptions o = request.options();
        BuildLog log = context.log();
        String apiUrl = o.get("api-url", "https://api.github.com");
        GitHubClient gh = new GitHubClient(apiUrl, o.get("uploads-url", uploadsUrl(apiUrl)), o.require("repository"),
                token(o).orElseThrow());
        String workflow = o.get("workflow", DEFAULT_WORKFLOW);
        Duration pollInterval = o.getDuration("poll-interval", Duration.ofSeconds(15));
        String correlationId = request.buildId() + "-" + NativeMatrixOrchestrator.sanitize(request.targetName());

        // 1. the workflow must exist on the repository
        if (gh.getOptional("actions/workflows/" + workflow).isEmpty()) {
            Path copy = writeWorkflow(request.outputDirectory().getParent().resolve("github"));
            throw new NativeBuildException("Workflow '" + workflow + "' not found in " + gh.repository()
                    + ". Commit " + copy + " as .github/workflows/" + workflow + " and run the build again.");
        }
        String ref = o.get("ref").orElse(null);
        if (ref == null) {
            ref = gh.get("").path("default_branch").asText("main");
        }

        // 2. ship the native sources through a draft release
        Path zip = Archives.zip(request.nativeSourcesDirectory(), context.workDirectory().resolve("native-sources.zip"),
                Map.of(ArgFile.DEFAULT_NAME, ArgFile.render(request.nativeImageArgs()).getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("tag_name", "native-matrix-" + correlationId);
        release.put("name", "native-matrix " + correlationId + " (temporary)");
        release.put("body", "Temporary draft created by the Quarkus Native Matrix extension to transfer native sources."
                + " It is deleted automatically and can be removed safely.");
        release.put("draft", true);
        release.put("target_commitish", ref);
        long releaseId = gh.post("releases", release).path("id").asLong();
        Long runId = null;
        try {
            log.infof("Uploading the native sources (%d MB)", Files.size(zip) / (1024 * 1024));
            String assetUrl = gh.uploadReleaseAsset(releaseId, "native-sources.zip", zip).path("url").asText();

            // 3. dispatch the workflow
            String runsOn = o.get("runs-on").orElseGet(() -> DEFAULT_RUNS_ON.get(request.platform()));
            if (runsOn == null) {
                throw new NativeBuildException("No default GitHub runner for " + request.platform()
                        + ", set the 'runs-on' option");
            }
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("correlation_id", correlationId);
            inputs.put("target", request.targetName());
            inputs.put("runs_on", runsOn);
            inputs.put("sources_url", assetUrl);
            inputs.put("java_version", o.get("java-version").orElseGet(() -> javaVersion(request.graalVmVersion())));
            inputs.put("distribution", o.get("distribution", "graalvm-community"));
            inputs.put("retention_days", o.get("artifact-retention-days", "1"));
            gh.post("actions/workflows/" + workflow + "/dispatches", Map.of("ref", ref, "inputs", inputs));
            log.infof("Dispatched %s on %s (runs-on: %s)", workflow, ref, runsOn);

            // 4. find the run and wait for it
            JsonNode run = findRun(gh, workflow, correlationId, o.getDuration("dispatch-timeout", Duration.ofMinutes(3)),
                    context);
            runId = run.path("id").asLong();
            String url = run.path("html_url").asText();
            log.info("Workflow run: " + url);
            String lastStatus = "";
            while (true) {
                context.checkCancelled();
                String status = run.path("status").asText();
                if (!status.equals(lastStatus)) {
                    log.info("Run status: " + status);
                    lastStatus = status;
                }
                if ("completed".equals(status)) {
                    break;
                }
                Thread.sleep(pollInterval.toMillis());
                run = gh.get("actions/runs/" + runId);
            }
            String conclusion = run.path("conclusion").asText();
            if (!"success".equals(conclusion)) {
                runId = null; // nothing to cancel
                throw new NativeBuildException("GitHub Actions run " + conclusion + ": " + url);
            }
            runId = null;

            // 5. download the artifact
            String artifactName = "native-" + correlationId;
            JsonNode artifact = null;
            for (JsonNode a : gh.get("actions/runs/" + run.path("id").asLong() + "/artifacts").path("artifacts")) {
                if (artifactName.equals(a.path("name").asText())) {
                    artifact = a;
                }
            }
            if (artifact == null) {
                throw new NativeBuildException("Artifact " + artifactName + " not found in " + url);
            }
            log.info("Downloading the executable");
            try (InputStream in = gh.download(artifact.path("archive_download_url").asText())) {
                Archives.unzip(in, request.outputDirectory());
            }
            return RunnerSupport.collectResult(request).withMetadata("run", url);
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                if (runId != null) {
                    log.info("Cancelling workflow run " + runId);
                    gh.post("actions/runs/" + runId + "/cancel", Map.of());
                }
            } catch (IOException | InterruptedException e) {
                log.warn("Could not cancel run " + runId + ": " + e.getMessage());
            }
            try {
                if (!o.getBoolean("keep-release", false)) {
                    gh.delete("releases/" + releaseId);
                }
            } catch (IOException | InterruptedException e) {
                log.warn("Could not delete draft release " + releaseId + ": " + e.getMessage());
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static JsonNode findRun(GitHubClient gh, String workflow, String correlationId, Duration timeout,
            BuildContext context) throws IOException, InterruptedException, NativeBuildException {
        long deadline = System.nanoTime() + timeout.toNanos();
        long delay = 2000;
        while (System.nanoTime() < deadline) {
            context.checkCancelled();
            JsonNode runs = gh.get("actions/workflows/" + workflow + "/runs?event=workflow_dispatch&per_page=50");
            for (JsonNode run : runs.path("workflow_runs")) {
                if (run.path("display_title").asText().contains(correlationId)) {
                    return run;
                }
            }
            Thread.sleep(delay);
            delay = Math.min(delay * 2, 10_000);
        }
        throw new NativeBuildException("The dispatched workflow run did not show up within " + timeout
                + ". Check that the workflow file is the one generated by quarkus-native-matrix (its run-name must"
                + " contain the correlation id)");
    }

    static Optional<String> token(RunnerOptions o) {
        return o.get("token")
                .or(() -> Optional.ofNullable(System.getenv("GITHUB_TOKEN")).filter(s -> !s.isBlank()))
                .or(() -> Optional.ofNullable(System.getenv("GH_TOKEN")).filter(s -> !s.isBlank()));
    }

    static String uploadsUrl(String apiUrl) {
        URI uri = URI.create(apiUrl);
        if ("api.github.com".equals(uri.getHost())) {
            return "https://uploads.github.com";
        }
        // GitHub Enterprise Server: https://HOST/api/v3 -> https://HOST/api/uploads
        String s = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        return s.endsWith("/api/v3") ? s.substring(0, s.length() - "v3".length()) + "uploads" : s;
    }

    /**
     * GraalVM releases are aligned with JDK releases since GraalVM for JDK 17: 23.0 = JDK 20/17, 23.1 = JDK 21, then
     * 24.0 = JDK 22, 24.1 = 23, 24.2 = 24, and 25.0 = JDK 25.
     */
    static String javaVersion(Optional<String> graalVmVersion) {
        if (graalVmVersion.isEmpty()) {
            return "25";
        }
        String[] parts = graalVmVersion.get().split("[.\\-]");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            if (major >= 25) {
                return Integer.toString(major);
            }
            if (major == 24) {
                return Integer.toString(22 + minor);
            }
            if (major == 23) {
                return minor >= 1 ? "21" : "17";
            }
            if (major >= 17) {
                return Integer.toString(major);
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        return "25";
    }

    static Path writeWorkflow(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(DEFAULT_WORKFLOW);
        try (InputStream in = GitHubActionsRunner.class.getResourceAsStream(WORKFLOW_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing resource " + WORKFLOW_RESOURCE);
            }
            Files.copy(in, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }
}
