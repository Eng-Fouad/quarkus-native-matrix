package io.quarkiverse.nativematrix.deployment.orchestrator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
            Optional<String> windowsCommand, List<String> args, boolean skipTests, Set<String> excludedDirectories) {
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
        return new FullBuild(root, posix, windows, executableDirectory, settings.excludedDirectories());
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
