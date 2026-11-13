package io.quarkiverse.nativematrix.deployment.runners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.quarkiverse.nativematrix.deployment.TestSupport;
import io.quarkiverse.nativematrix.deployment.orchestrator.NativeSources;
import io.quarkiverse.nativematrix.spi.NativeBuildRequest;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.RunnerOptions;

/**
 * Runs the GitHub Actions runner against a fake GitHub REST API.
 */
class GitHubActionsRunnerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    private HttpServer server;
    private String base;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicReference<JsonNode> dispatched = new AtomicReference<>();
    private final AtomicReference<byte[]> uploaded = new AtomicReference<>();
    private final AtomicInteger runPolls = new AtomicInteger();
    private volatile boolean workflowExists = true;
    private volatile String conclusion = "success";
    private volatile String blobAuthorization;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        calls.add(method + " " + path);
        byte[] body = ex.getRequestBody().readAllBytes();
        if (!path.startsWith("/blob") && !"Bearer secret".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            send(ex, 401, "{\"message\":\"Bad credentials\"}");
            return;
        }
        final String repo = "/repos/acme/app";
        String correlation = Optional.ofNullable(dispatched.get())
                .map(d -> d.path("inputs").path("correlation_id").asText()).orElse("");
        switch (method + " " + path) {
            case "GET " + repo + "/actions/workflows/native-matrix.yml" ->
                send(ex, workflowExists ? 200 : 404, workflowExists ? "{\"id\":1}" : "{\"message\":\"Not Found\"}");
            case "GET " + repo -> send(ex, 200, "{\"default_branch\":\"trunk\"}");
            case "POST " + repo + "/releases" -> send(ex, 201, "{\"id\":42}");
            case "POST /uploads" + repo + "/releases/42/assets" -> {
                uploaded.set(body);
                send(ex, 201, "{\"url\":\"" + base + repo + "/releases/assets/7\"}");
            }
            case "POST " + repo + "/actions/workflows/native-matrix.yml/dispatches" -> {
                dispatched.set(JSON.readTree(body));
                send(ex, 204, "");
            }
            case "GET " + repo + "/actions/workflows/native-matrix.yml/runs" -> send(ex, 200,
                    "{\"workflow_runs\":[{\"id\":5,\"display_title\":\"native-matrix other x\",\"status\":\"queued\"},"
                            + "{\"id\":9,\"display_title\":\"native-matrix linux-arm64 " + correlation
                            + "\",\"status\":\"queued\",\"html_url\":\"https://github.com/acme/app/actions/runs/9\"}]}");
            case "GET " + repo + "/actions/runs/9" -> {
                boolean done = runPolls.incrementAndGet() >= 2;
                send(ex, 200, "{\"id\":9,\"status\":\"" + (done ? "completed" : "in_progress") + "\",\"conclusion\":"
                        + (done ? "\"" + conclusion + "\"" : "null")
                        + ",\"html_url\":\"https://github.com/acme/app/actions/runs/9\"}");
            }
            case "GET " + repo + "/actions/runs/9/artifacts" -> send(ex, 200,
                    "{\"artifacts\":[{\"name\":\"native-" + correlation + "\",\"archive_download_url\":\"" + base
                            + repo + "/actions/artifacts/3/zip\"}]}");
            case "GET " + repo + "/actions/artifacts/3/zip" -> {
                ex.getResponseHeaders().add("Location", base + "/blob/3");
                send(ex, 302, "");
            }
            case "GET /blob/3" -> {
                blobAuthorization = ex.getRequestHeaders().getFirst("Authorization");
                ex.sendResponseHeaders(200, 0);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(zip(Map.of("app-runner", "github executable", "libawt.so", "lib")));
                }
            }
            case "DELETE " + repo + "/releases/42" -> send(ex, 204, "");
            case "POST " + repo + "/actions/runs/9/cancel" -> send(ex, 202, "{}");
            default -> send(ex, 404, "{\"message\":\"Not Found\"}");
        }
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            for (var e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private NativeBuildRequest request() throws Exception {
        NativeSources sources = NativeSources.read(TestSupport.nativeSources(tmp));
        return NativeBuildRequest.builder()
                .buildId("b1").targetName("linux-arm64").platform(Platform.parse("linux-arm64"))
                .nativeSourcesDirectory(sources.directory()).outputName(sources.outputName())
                .nativeImageArgs(sources.args())
                .graalVmVersion("24.2")
                .options(RunnerOptions.of(Map.of(
                        "repository", "acme/app",
                        "token", "secret",
                        "api-url", base,
                        "uploads-url", base + "/uploads",
                        "poll-interval", "10ms")))
                .outputDirectory(Files.createDirectories(tmp.resolve("out/linux-arm64")))
                .build();
    }

    @Test
    void dispatchesTheWorkflowAndDownloadsTheExecutable() throws Exception {
        NativeBuildRequest request = request();
        var result = new GitHubActionsRunner().build(request,
                TestSupport.context(Files.createDirectories(tmp.resolve("work"))));

        assertThat(result.executable()).hasContent("github executable");
        assertThat(result.additionalFiles()).extracting(p -> p.getFileName().toString()).containsExactly("libawt.so");
        assertThat(result.metadata()).containsEntry("run", "https://github.com/acme/app/actions/runs/9");

        JsonNode d = dispatched.get();
        assertThat(d.path("ref").asText()).isEqualTo("trunk");
        assertThat(d.path("inputs").path("runs_on").asText()).isEqualTo("ubuntu-24.04-arm");
        assertThat(d.path("inputs").path("java_version").asText()).isEqualTo("24");
        assertThat(d.path("inputs").path("correlation_id").asText()).isEqualTo("b1-linux-arm64");
        assertThat(d.path("inputs").path("sources_url").asText()).endsWith("/releases/assets/7");

        // the uploaded zip holds the native sources and the target specific argument file
        Path unzipped = tmp.resolve("unzipped");
        Files.write(tmp.resolve("uploaded.zip"), uploaded.get());
        io.quarkiverse.nativematrix.spi.support.Archives.unzip(tmp.resolve("uploaded.zip"), unzipped);
        assertThat(unzipped.resolve("lib/main/dep.jar")).exists();
        assertThat(unzipped.resolve("native-image.matrix.args")).content().contains("output/app-runner");

        // the token is never sent to the storage host, and the draft release is deleted
        assertThat(blobAuthorization).isNull();
        assertThat(calls).contains("DELETE /repos/acme/app/releases/42").doesNotContain(
                "POST /repos/acme/app/actions/runs/9/cancel");
    }

    @Test
    void reportsFailedRunsAndCleansUp() throws Exception {
        conclusion = "failure";
        NativeBuildRequest request = request();
        assertThatThrownBy(() -> new GitHubActionsRunner().build(request,
                TestSupport.context(Files.createDirectories(tmp.resolve("work")))))
                .hasMessage("GitHub Actions run failure: https://github.com/acme/app/actions/runs/9");
        assertThat(calls).contains("DELETE /repos/acme/app/releases/42");
    }

    @Test
    void writesTheWorkflowWhenItIsMissing() throws Exception {
        workflowExists = false;
        NativeBuildRequest request = request();
        assertThatThrownBy(() -> new GitHubActionsRunner().build(request,
                TestSupport.context(Files.createDirectories(tmp.resolve("work")))))
                .hasMessageContaining("Workflow 'native-matrix.yml' not found in acme/app")
                .hasMessageContaining(".github/workflows/native-matrix.yml");
        assertThat(tmp.resolve("out/github/native-matrix.yml")).content().contains("workflow_dispatch",
                "native-image @native-image.matrix.args");
        assertThat(calls).noneMatch(c -> c.contains("releases"));
    }

    @Test
    void derivesSettings() {
        assertThat(GitHubActionsRunner.javaVersion(Optional.of("25.0"))).isEqualTo("25");
        assertThat(GitHubActionsRunner.javaVersion(Optional.of("23.1.2"))).isEqualTo("21");
        assertThat(GitHubActionsRunner.javaVersion(Optional.empty())).isEqualTo("25");
        assertThat(GitHubActionsRunner.uploadsUrl("https://api.github.com")).isEqualTo("https://uploads.github.com");
        assertThat(GitHubActionsRunner.uploadsUrl("https://ghe.acme.com/api/v3"))
                .isEqualTo("https://ghe.acme.com/api/uploads");
    }
}
