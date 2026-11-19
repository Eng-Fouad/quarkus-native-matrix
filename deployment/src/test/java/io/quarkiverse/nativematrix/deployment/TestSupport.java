package io.quarkiverse.nativematrix.deployment;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import io.quarkiverse.nativematrix.deployment.orchestrator.NativeSources;
import io.quarkiverse.nativematrix.spi.BuildContext;
import io.quarkiverse.nativematrix.spi.BuildLog;
import io.quarkus.deployment.pkg.builditem.NativeImageSourcesBuildItem;

public final class TestSupport {

    /** The arguments Quarkus would write, including one with regex characters. */
    public static final List<String> ARGS = List.of("-J-Dfile.encoding=UTF-8",
            "--enable-monitoring=heapdump,threaddump", "--exclude-config", "\\Qio.netty\\E", "^/META-INF/native-image/.*",
            "-o", "app-runner", "-jar", "app-runner.jar");

    private TestSupport() {
    }

    /**
     * Creates a fake {@code target/native-sources} directory.
     */
    public static Path nativeSources(Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("native-sources"));
        Files.writeString(dir.resolve("app-runner.jar"), "jar");
        Files.createDirectories(dir.resolve("lib/main"));
        Files.writeString(dir.resolve("lib/main/dep.jar"), "dep");
        return dir;
    }

    /**
     * Creates a fake {@code target/native-sources} directory and describes it like Quarkus does with
     * {@link NativeImageSourcesBuildItem}.
     */
    public static NativeSources sources(Path root) throws IOException {
        return sources(root, ARGS);
    }

    public static NativeSources sources(Path root, List<String> args) throws IOException {
        return NativeSources.of(new NativeImageSourcesBuildItem(nativeSources(root), "app-runner.jar", "app-runner",
                args, "25.0", Optional.empty()));
    }

    /**
     * @return a command running {@code FakeNativeImage.java} with the current JVM
     */
    public static List<String> fakeNativeImageCommand() {
        try {
            Path source = Path.of(TestSupport.class.getResource("/fake-native-image/FakeNativeImage.java").toURI());
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            return List.of(java.toString(), source.toString());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static RecordingContext context(Path workDir) {
        return new RecordingContext(workDir);
    }

    /**
     * A build context recording the native-image versions reported by runners.
     */
    public static final class RecordingContext implements BuildContext {
        private final Path workDir;
        private final List<String> versions = new java.util.concurrent.CopyOnWriteArrayList<>();

        RecordingContext(Path workDir) {
            this.workDir = workDir;
        }

        public List<String> versions() {
            return versions;
        }

        @Override
        public BuildLog log() {
            return BuildLog.console("test");
        }

        @Override
        public Path workDirectory() {
            return workDir;
        }

        @Override
        public boolean isCancelled() {
            return Thread.currentThread().isInterrupted();
        }

        @Override
        public void reportNativeImageVersion(String versionOutput) {
            versions.add(versionOutput);
        }
    }

    /** The output of {@code native-image --version} of Mandrel 25.0.1. */
    public static final String MANDREL_VERSION_OUTPUT = """
            native-image 25.0.1 2025-10-21
            OpenJDK Runtime Environment Mandrel-25.0.1.0-Final (build 25.0.1+8)
            OpenJDK 64-Bit Server VM Mandrel-25.0.1.0-Final (build 25.0.1+8, mixed mode)
            """;
}
