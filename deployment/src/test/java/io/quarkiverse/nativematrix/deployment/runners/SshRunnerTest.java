package io.quarkiverse.nativematrix.deployment.runners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.nativematrix.deployment.TestSupport;
import io.quarkiverse.nativematrix.deployment.orchestrator.NativeSources;
import io.quarkiverse.nativematrix.spi.NativeBuildRequest;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.RunnerOptions;

/**
 * Runs the SSH runner against an in-process Apache MINA SSHD server whose exec channel emulates native-image.
 */
class SshRunnerTest {

    @TempDir
    Path tmp;

    private SshServer server;
    private Path home;
    private Path clientKey;
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private volatile int exitCode = 0;

    @BeforeEach
    void startServer() throws Exception {
        home = Files.createDirectories(tmp.resolve("remote-home"));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        KeyPair keyPair = generator.generateKeyPair();
        clientKey = tmp.resolve("id_test");
        try (OutputStream os = Files.newOutputStream(clientKey)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, "test", null, os);
        }

        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("host.ser")));
        server.setPublickeyAuthenticator((user, key, session) -> KeyUtils.compareKeys(key, keyPair.getPublic()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(home));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setCommandFactory((channel, command) -> new FakeNativeImageCommand(command));
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.stop(true);
    }

    /**
     * Parses "cd '
     * <dir>
     * ' && ... native-image @file", reads the argument file and writes the -o output.
     */
    private class FakeNativeImageCommand implements Command {
        private final String command;
        private OutputStream out;
        private ExitCallback callback;

        FakeNativeImageCommand(String command) {
            this.command = command;
        }

        @Override
        public void start(ChannelSession channel, Environment env) {
            commands.add(command);
            new Thread(() -> {
                try {
                    Matcher m = Pattern.compile("^cd '([^']+)' && .*native-image'? @(\\S+)$").matcher(command);
                    if (!m.matches()) {
                        out.write(("unexpected command " + command + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        callback.onExit(127);
                        return;
                    }
                    Path dir = home.resolve(m.group(1));
                    List<String> args = Files.readAllLines(dir.resolve(m.group(2)));
                    Path output = dir.resolve(args.get(args.indexOf("-o") + 1));
                    Files.createDirectories(output.getParent());
                    Files.writeString(output, "remote executable");
                    out.write("[1/8] Initializing...\n[8/8] Done\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    callback.onExit(exitCode);
                } catch (IOException e) {
                    callback.onExit(1, e.getMessage());
                }
            }).start();
        }

        @Override
        public void destroy(ChannelSession channel) {
        }

        @Override
        public void setInputStream(InputStream in) {
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.callback = callback;
        }
    }

    private NativeBuildRequest request(Map<String, String> extraOptions) throws Exception {
        NativeSources sources = NativeSources.read(TestSupport.nativeSources(tmp));
        var options = new java.util.HashMap<>(Map.of(
                "host", "127.0.0.1",
                "port", Integer.toString(server.getPort()),
                "user", "builder",
                "identity-file", clientKey.toString(),
                "strict-host-key-checking", "false",
                "graalvm-home", "/opt/graalvm",
                "pre-command", "echo ready"));
        options.putAll(extraOptions);
        return NativeBuildRequest.builder()
                .buildId("b1").targetName("macos-arm64").platform(Platform.parse("macos-arm64"))
                .nativeSourcesDirectory(sources.directory()).outputName(sources.outputName())
                .nativeImageArgs(sources.args())
                .options(RunnerOptions.of(options))
                .outputDirectory(Files.createDirectories(tmp.resolve("out")))
                .build();
    }

    @Test
    void uploadsBuildsDownloadsAndCleansUp() throws Exception {
        NativeBuildRequest request = request(Map.of());
        var result = new SshRunner().build(request, TestSupport.context(Files.createDirectories(tmp.resolve("work"))));

        assertThat(result.executable()).hasContent("remote executable");
        assertThat(result.metadata()).containsEntry("host", "127.0.0.1");
        assertThat(commands).containsExactly("cd '.quarkus-native-matrix/b1-macos-arm64' && echo ready && exec"
                + " '/opt/graalvm/bin/native-image' @native-image.matrix.args");
        // remote work directory removed
        assertThat(home.resolve(".quarkus-native-matrix/b1-macos-arm64")).doesNotExist();
    }

    @Test
    void keepsRemoteFilesOnRequestAndReportsFailures() throws Exception {
        exitCode = 3;
        NativeBuildRequest request = request(Map.of("keep-remote-files", "true"));
        assertThatThrownBy(() -> new SshRunner().build(request,
                TestSupport.context(Files.createDirectories(tmp.resolve("work")))))
                .hasMessageContaining("exit code 3");
        Path remote = home.resolve(".quarkus-native-matrix/b1-macos-arm64");
        assertThat(remote.resolve("app-runner.jar")).exists();
        assertThat(remote.resolve("lib/main/dep.jar")).exists();
        assertThat(remote.resolve("native-image.matrix.args")).content().contains("\\Qio.netty\\E");
    }

    @Test
    void rejectsUnknownHostsByDefault() throws Exception {
        NativeBuildRequest request = request(Map.of("strict-host-key-checking", "true",
                "known-hosts", tmp.resolve("empty_known_hosts").toString()));
        assertThatThrownBy(() -> new SshRunner().build(request,
                TestSupport.context(Files.createDirectories(tmp.resolve("work")))))
                .isInstanceOf(Exception.class);
        assertThat(commands).isEmpty();
    }

    @Test
    void buildsWindowsCommands() {
        RunnerOptions o = RunnerOptions.of(Map.of("graalvm-home", "C:\\graalvm",
                "pre-command", "call \"C:\\VS\\vcvars64.bat\""));
        assertThat(SshRunner.windowsCommand(o, ".quarkus-native-matrix/b1-win"))
                .isEqualTo("cd /d \".quarkus-native-matrix\\b1-win\" && call \"C:\\VS\\vcvars64.bat\" &&"
                        + " \"C:\\graalvm\\bin\\native-image.cmd\" @native-image.matrix.args");
        assertThat(SshRunner.posixCommand(RunnerOptions.empty(), "it's/dir"))
                .isEqualTo("cd 'it'\"'\"'s/dir' && exec native-image @native-image.matrix.args");
    }
}
