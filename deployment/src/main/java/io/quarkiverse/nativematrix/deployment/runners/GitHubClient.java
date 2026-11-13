package io.quarkiverse.nativematrix.deployment.runners;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The few GitHub REST API calls the {@link GitHubActionsRunner} needs, on top of the JDK HTTP client.
 */
final class GitHubClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String apiUrl;
    private final String uploadsUrl;
    private final String repository;
    private final String token;

    GitHubClient(String apiUrl, String uploadsUrl, String repository, String token) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                // never forward the token to the storage hosts GitHub redirects downloads to
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.apiUrl = stripSlash(apiUrl);
        this.uploadsUrl = stripSlash(uploadsUrl);
        this.repository = repository;
        this.token = token;
    }

    static final class GitHubException extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;

        GitHubException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    Optional<JsonNode> getOptional(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = send(request(apiUrl + repoPath(path)).GET().build());
        if (r.statusCode() == 404) {
            return Optional.empty();
        }
        return Optional.of(parse(r));
    }

    JsonNode get(String path) throws IOException, InterruptedException {
        return parse(send(request(apiUrl + repoPath(path)).GET().build()));
    }

    JsonNode post(String path, Object body) throws IOException, InterruptedException {
        return parse(send(request(apiUrl + repoPath(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build()));
    }

    void delete(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = send(request(apiUrl + repoPath(path)).DELETE().build());
        if (r.statusCode() != 404) {
            check(r);
        }
    }

    JsonNode uploadReleaseAsset(long releaseId, String name, Path file) throws IOException, InterruptedException {
        String url = uploadsUrl + "/repos/" + repository + "/releases/" + releaseId + "/assets?name="
                + URLEncoder.encode(name, StandardCharsets.UTF_8);
        return parse(send(request(url)
                .header("Content-Type", "application/zip")
                .timeout(Duration.ofMinutes(30))
                .POST(HttpRequest.BodyPublishers.ofFile(file))
                .build()));
    }

    /**
     * Downloads a URL answering with a redirect to a pre-signed storage URL (artifacts, release assets).
     */
    InputStream download(String url) throws IOException, InterruptedException {
        HttpResponse<InputStream> r = http.send(request(url).timeout(Duration.ofMinutes(30)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        int hops = 0;
        while (r.statusCode() / 100 == 3 && hops++ < 5) {
            String location = r.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("Redirect without Location from " + url));
            r.body().close();
            r = http.send(HttpRequest.newBuilder(URI.create(location)).timeout(Duration.ofMinutes(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        }
        if (r.statusCode() / 100 != 2) {
            r.body().close();
            throw new GitHubException(r.statusCode(), "Download of " + url + " failed with HTTP " + r.statusCode());
        }
        return r.body();
    }

    String repository() {
        return repository;
    }

    private String repoPath(String path) {
        return path.startsWith("/") ? path : "/repos/" + repository + (path.isEmpty() ? "" : "/" + path);
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + token)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "quarkus-native-matrix");
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonNode parse(HttpResponse<String> r) throws IOException {
        check(r);
        String body = r.body();
        return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
    }

    private static void check(HttpResponse<String> r) throws GitHubException {
        if (r.statusCode() / 100 != 2) {
            String message;
            try {
                JsonNode n = JSON.readTree(r.body());
                message = n.path("message").asText(r.body());
            } catch (IOException | RuntimeException e) {
                message = r.body();
            }
            throw new GitHubException(r.statusCode(), r.request().method() + " " + r.request().uri().getPath()
                    + " failed with HTTP " + r.statusCode() + ": " + message);
        }
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
