package io.quarkiverse.nativematrix.deployment.orchestrator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Collects the SNAPSHOT artifacts a full build needs but a builder cannot download: they were installed in the local
 * Maven repository of the build machine ({@code mvn install} of another project) and are not part of the project.
 * They are shipped with the project as a small repository read by Maven through {@code maven.repo.local.tail}
 * (Maven 3.9+) and by Gradle through an init script.
 */
public final class LocalSnapshots {

    /** Location of the shipped repository, relative to the project root. */
    public static final String REPOSITORY_DIRECTORY = ".native-matrix/repository";
    /** Gradle init script adding the shipped repository, relative to the project root. */
    public static final String GRADLE_INIT_SCRIPT = ".native-matrix/repository.init.gradle";

    /**
     * A resolved dependency.
     */
    public record Artifact(String groupId, String artifactId, String version, Path file) {
        boolean isSnapshot() {
            return version.endsWith("-SNAPSHOT");
        }
    }

    private LocalSnapshots() {
    }

    /**
     * @return the files to ship, by path relative to the project root
     */
    public static Map<String, Path> collect(List<Artifact> dependencies, Path projectRoot) throws IOException {
        Map<String, Path> files = new LinkedHashMap<>();
        for (Artifact a : dependencies) {
            if (!a.isSnapshot() || a.file() == null || a.file().toAbsolutePath().normalize().startsWith(projectRoot)) {
                // released artifacts can be downloaded, project modules are built by the builder
                continue;
            }
            Optional<Path> repository = repositoryRoot(a);
            if (repository.isPresent()) {
                addVersionDirectory(repository.get(), a.groupId(), a.artifactId(), a.version(), files);
            }
        }
        return files;
    }

    /**
     * Ships a SNAPSHOT POM (a parent POM resolved from the local repository) and its own SNAPSHOT parents.
     */
    public static void addPom(Path repository, String groupId, String artifactId, String version, Map<String, Path> files)
            throws IOException {
        addVersionDirectory(repository, groupId, artifactId, version, files);
    }

    /**
     * @return the local repository holding the artifact, derived from its {@code group/artifact/version/file} layout
     */
    static Optional<Path> repositoryRoot(Artifact a) {
        Path versionDir = a.file().toAbsolutePath().normalize().getParent();
        Path expected = Path.of(a.groupId().replace('.', '/'), a.artifactId(), a.version());
        Path dir = versionDir;
        for (int i = expected.getNameCount() - 1; i >= 0; i--) {
            if (dir == null || !dir.getFileName().toString().equals(expected.getName(i).toString())) {
                return Optional.empty();
            }
            dir = dir.getParent();
        }
        return Optional.ofNullable(dir);
    }

    /**
     * The local repository configured for Maven on this machine: {@code maven.repo.local}, else {@code ~/.m2/repository}.
     */
    public static Path defaultRepository() {
        return Path.of(System.getProperty("maven.repo.local",
                Path.of(System.getProperty("user.home"), ".m2", "repository").toString()));
    }

    private static void addVersionDirectory(Path repository, String groupId, String artifactId, String version,
            Map<String, Path> files) throws IOException {
        String relative = groupId.replace('.', '/') + "/" + artifactId + "/" + version;
        Path dir = repository.resolve(relative);
        if (!Files.isDirectory(dir)) {
            return;
        }
        String prefix = REPOSITORY_DIRECTORY + "/" + relative + "/";
        if (files.keySet().stream().anyMatch(k -> k.startsWith(prefix))) {
            return; // already added
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString();
                if (name.endsWith(".jar") || name.endsWith(".pom") || name.startsWith("maven-metadata")) {
                    files.put(prefix + name, f);
                }
            }
        }
        // the parent of a SNAPSHOT is often a SNAPSHOT of the same project
        Path pom = dir.resolve(artifactId + "-" + version + ".pom");
        if (Files.isRegularFile(pom)) {
            Optional<Poms.Parent> parent = Poms.parent(pom);
            if (parent.isPresent() && parent.get().version().endsWith("-SNAPSHOT")) {
                addVersionDirectory(repository, parent.get().groupId(), parent.get().artifactId(),
                        parent.get().version(), files);
            }
        }
    }

    /**
     * @return the Gradle init script declaring the shipped repository for every project
     */
    public static String gradleInitScript() {
        return """
                // Generated by quarkus-native-matrix: SNAPSHOT dependencies installed on the build machine
                allprojects {
                    repositories {
                        maven { url = uri("${rootDir}/%s") }
                    }
                }
                """.formatted(REPOSITORY_DIRECTORY);
    }

    static final Set<String> SHIPPED_EXTENSIONS = Set.of(".jar", ".pom");
}
