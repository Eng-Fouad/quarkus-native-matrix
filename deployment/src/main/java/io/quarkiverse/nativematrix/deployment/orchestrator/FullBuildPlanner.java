package io.quarkiverse.nativematrix.deployment.orchestrator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.quarkiverse.nativematrix.spi.FullBuild;

/**
 * Works out how to build the whole project on a builder ({@code mode=full}): where the project root is, which build
 * tool it uses, and the command producing the native executable of the current module.
 */
public final class FullBuildPlanner {

    /**
     * Properties passed to the remote build: a real native build (not native sources, not in a container) that does
     * not trigger the matrix again.
     */
    static final List<String> NATIVE_PROPERTIES = List.of(
            "-Dquarkus.native.enabled=true",
            "-Dquarkus.native.sources-only=false",
            "-Dquarkus.native.container-build=false",
            "-Dquarkus.native-matrix.enabled=false");

    public static final Set<String> DEFAULT_EXCLUDES = Set.of("target", "build", ".git", ".idea", ".vscode",
            ".gradle", "node_modules", ".quarkus");

    public enum BuildTool {
        MAVEN,
        GRADLE
    }

    /**
     * User settings, see {@code quarkus.native-matrix.full-build.*}.
     */
    public record Settings(Optional<Path> projectDirectory, Optional<BuildTool> buildTool, Optional<String> posixCommand,
            Optional<String> windowsCommand, List<String> args, boolean skipTests, Set<String> excludedDirectories,
            boolean shipSnapshots, List<LocalSnapshots.Artifact> dependencies, Optional<Path> localRepository) {

        public Settings(Optional<Path> projectDirectory, Optional<BuildTool> buildTool, Optional<String> posixCommand,
                Optional<String> windowsCommand, List<String> args, boolean skipTests, Set<String> excludedDirectories) {
            this(projectDirectory, buildTool, posixCommand, windowsCommand, args, skipTests, excludedDirectories, false,
                    List.of(), Optional.empty());
        }
    }

    private FullBuildPlanner() {
    }

    /**
     * @param buildOutputDirectory the output directory of the current build ({@code target/} or {@code build/} of the
     *        Quarkus module)
     */
    public static FullBuild plan(Path buildOutputDirectory, Settings settings) {
        Path output = buildOutputDirectory.toAbsolutePath().normalize();
        Path module = output.getParent();
        Path root = settings.projectDirectory().map(p -> p.toAbsolutePath().normalize()).orElseGet(() -> findRoot(module));
        if (!module.startsWith(root)) {
            throw new IllegalArgumentException("The Quarkus module " + module + " is not inside the project directory "
                    + root);
        }
        String moduleRelative = slashes(root.relativize(module));
        String executableDirectory = slashes(root.relativize(output));

        // what the builder could not download: SNAPSHOT dependencies and parent POMs only installed on this machine
        Path repository = settings.localRepository().orElseGet(() -> settings.dependencies().stream()
                .map(LocalSnapshots::repositoryRoot).flatMap(Optional::stream).findFirst()
                .orElseGet(LocalSnapshots::defaultRepository));
        Map<String, Path> additionalFiles = new LinkedHashMap<>();
        boolean mavenProject = Files.isRegularFile(module.resolve("pom.xml"));
        if (mavenProject) {
            checkParents(module.resolve("pom.xml"), root, repository, settings.shipSnapshots(), additionalFiles);
        }
        if (settings.shipSnapshots()) {
            try {
                additionalFiles.putAll(LocalSnapshots.collect(settings.dependencies(), root));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        String posix;
        String windows;
        if (settings.posixCommand().isPresent() && settings.windowsCommand().isPresent()) {
            posix = settings.posixCommand().get();
            windows = settings.windowsCommand().get();
        } else {
            BuildTool tool = settings.buildTool().orElseGet(() -> detect(root).orElseThrow(
                    () -> new IllegalArgumentException("Cannot find a Maven or Gradle build in " + root
                            + "; set quarkus.native-matrix.full-build.project-directory or the full-build commands")));
            List<String> arguments = new ArrayList<>();
            if (tool == BuildTool.MAVEN) {
                arguments.add("-B");
                arguments.add("package");
                if (settings.skipTests()) {
                    arguments.add("-DskipTests");
                }
                if (!moduleRelative.isEmpty()) {
                    arguments.addAll(List.of("-pl", moduleRelative, "-am"));
                }
            } else {
                arguments.add(moduleRelative.isEmpty() ? "build" : ":" + moduleRelative.replace('/', ':') + ":build");
                if (settings.skipTests()) {
                    arguments.addAll(List.of("-x", "test"));
                }
            }
            arguments.addAll(NATIVE_PROPERTIES);
            if (!additionalFiles.isEmpty()) {
                if (tool == BuildTool.MAVEN) {
                    // Maven 3.9+: extra read-only local repositories
                    arguments.add("-Dmaven.repo.local.tail=" + LocalSnapshots.REPOSITORY_DIRECTORY);
                } else {
                    Path init = output.resolve("native-matrix-full-build").resolve("repository.init.gradle");
                    try {
                        Files.createDirectories(init.getParent());
                        Files.writeString(init, LocalSnapshots.gradleInitScript());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    additionalFiles.put(LocalSnapshots.GRADLE_INIT_SCRIPT, init);
                    arguments.addAll(List.of("--init-script", LocalSnapshots.GRADLE_INIT_SCRIPT));
                }
            }
            arguments.addAll(settings.args());
            String args = String.join(" ", arguments);
            boolean maven = tool == BuildTool.MAVEN;
            String posixLauncher = Files.isRegularFile(root.resolve(maven ? "mvnw" : "gradlew"))
                    ? "sh ./" + (maven ? "mvnw" : "gradlew")
                    : (maven ? "mvn" : "gradle");
            // 'call' is required for batch files invoked from batch files (GitHub Actions runs cmd steps as one)
            String windowsLauncher = Files.isRegularFile(root.resolve(maven ? "mvnw.cmd" : "gradlew.bat"))
                    ? "call " + (maven ? "mvnw.cmd" : "gradlew.bat")
                    : "call " + (maven ? "mvn" : "gradle");
            posix = settings.posixCommand().orElse(posixLauncher + " " + args);
            windows = settings.windowsCommand().orElse(windowsLauncher + " " + args);
        }
        return new FullBuild(root, posix, windows, executableDirectory, settings.excludedDirectories(), additionalFiles);
    }

    /**
     * Follows the {@code <parent>} chain of the module: local parents must be inside the shipped project directory,
     * SNAPSHOT parents resolved from the local repository are shipped with the project.
     */
    static void checkParents(Path modulePom, Path root, Path repository, boolean shipSnapshots,
            Map<String, Path> additionalFiles) {
        Path pom = modulePom;
        for (int depth = 0; depth < 50; depth++) {
            Optional<Poms.Parent> parent = Poms.parent(pom);
            if (parent.isEmpty()) {
                return;
            }
            Poms.Parent p = parent.get();
            Path pomDir = pom.getParent();
            Optional<Path> local = p.relativePath().map(r -> {
                Path candidate = pomDir.resolve(r).normalize();
                return Files.isDirectory(candidate) ? candidate.resolve("pom.xml") : candidate;
            }).filter(Files::isRegularFile).filter(c -> p.artifactId().equals(Poms.artifactId(c)));
            if (local.isPresent()) {
                Path parentPom = local.get().toAbsolutePath().normalize();
                if (!parentPom.startsWith(root)) {
                    throw new IllegalArgumentException("The parent POM " + parentPom + " (" + p.groupId() + ":"
                            + p.artifactId() + ") is outside the project directory " + root
                            + " shipped to the builders; set quarkus.native-matrix.full-build.project-directory to "
                            + commonAncestor(root, parentPom.getParent()));
                }
                pom = parentPom;
                continue;
            }
            // resolved from a repository: only a SNAPSHOT installed on this machine needs to be shipped
            if (shipSnapshots && p.version().endsWith("-SNAPSHOT")) {
                try {
                    LocalSnapshots.addPom(repository, p.groupId(), p.artifactId(), p.version(), additionalFiles);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return;
        }
    }

    private static Path commonAncestor(Path a, Path b) {
        Path ancestor = a;
        while (ancestor != null && !b.startsWith(ancestor)) {
            ancestor = ancestor.getParent();
        }
        return ancestor == null ? b : ancestor;
    }

    /**
     * The project root: the directory holding {@code settings.gradle(.kts)} for Gradle, else the top-most directory of
     * the chain of {@code pom.xml} files above the module, else the module itself.
     */
    static Path findRoot(Path module) {
        for (Path dir = module; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("settings.gradle"))
                    || Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
                return dir;
            }
        }
        // Maven marks the root of a multi-module project with .mvn (also where the wrapper lives); look for it up to the
        // repository root, so that modules nested in plain directories (integration-tests/app) are found too
        for (Path dir = module; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(".mvn")) || Files.isRegularFile(dir.resolve("mvnw"))) {
                return dir;
            }
            if (Files.exists(dir.resolve(".git"))) {
                break;
            }
        }
        Path root = module;
        for (Path dir = module.getParent(); dir != null && Files.isRegularFile(dir.resolve("pom.xml")); dir = dir.getParent()) {
            root = dir;
        }
        return root;
    }

    static Optional<BuildTool> detect(Path root) {
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return Optional.of(BuildTool.MAVEN);
        }
        for (String f : new String[] { "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts" }) {
            if (Files.isRegularFile(root.resolve(f))) {
                return Optional.of(BuildTool.GRADLE);
            }
        }
        return Optional.empty();
    }

    private static String slashes(Path p) {
        return p.toString().replace('\\', '/');
    }
}
