package io.quarkiverse.nativematrix.deployment;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.quarkiverse.nativematrix.spi.BuildContext;
import io.quarkiverse.nativematrix.spi.BuildLog;

public final class TestSupport {

    /** The arguments Quarkus would write, including one with regex characters. */
    public static final String ARGS = "-J-Dfile.encoding=UTF-8 --enable-monitoring=heapdump,threaddump"
            + " --exclude-config \\Qio.netty\\E ^/META-INF/native-image/.* -o app-runner -jar app-runner.jar";

    private TestSupport() {
    }

    /**
     * Creates a fake {@code target/native-sources} directory.
     */
    public static Path nativeSources(Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("native-sources"));
        Files.writeString(dir.resolve("native-image.args"), ARGS);
        Files.writeString(dir.resolve("graalvm.version"), "25.0");
        Files.writeString(dir.resolve("app-runner.jar"), "jar");
        Files.createDirectories(dir.resolve("lib/main"));
        Files.writeString(dir.resolve("lib/main/dep.jar"), "dep");
        return dir;
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

    public static BuildContext context(Path workDir) {
        return new BuildContext() {
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
        };
    }
}
