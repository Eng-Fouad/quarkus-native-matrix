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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.quarkiverse.nativematrix.deployment.TestSupport;
import io.quarkiverse.nativematrix.deployment.orchestrator.NativeSources;
import io.quarkiverse.nativematrix.spi.NativeBuildRequest;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.RunnerOptions;
import io.quarkiverse.nativematrix.spi.support.FileTrees;

/**
 * Runs the SSH runner, with both the embedded client and the OpenSSH executables, against an in-process Apache MINA
 * SSHD server whose exec channel emulates a POSIX shell with native-image installed.
 */
class SshRunnerTest {

    @TempDir
    Path tmp;

    private SshServer server;
    private Path home;
    private Path clientKey;
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private volatile int exitCode = 0;

    static Stream<String> clients() {
        return OpenSshTransport.available() ? Stream.of("mina", "openssh") : Stream.of("mina");
    }

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
        server.setCommandFactory((channel, command) -> new FakeShell(command));
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.stop(true);
    }

    /**
     * Understands the commands of the runner: mkdir -p, rm -rf, sh &lt;script&gt;, native-image --version and
     * "cd &lt;dir&gt; &amp;&amp; ... native-image @argfile".
     */
    private class FakeShell implements Command {
        private final String command;
        private OutputStream out;
        private ExitCallback callback;

        FakeShell(String command) {
            this.command = command;
        }

        @Override
        public void start(ChannelSession channel, Environment env) {
            commands.add(command);
            new Thread(() -> {
                try {
                    callback.onExit(execute(command));
                } catch (IOException e) {
                    callback.onExit(1, e.getMessage());
                }
            }).start();
        }

        private int execute(String line) throws IOException {
            Matcher m;
            if ((m = Pattern.compile("^mkdir -p '([^']+)'$").matcher(line)).matches()) {
                Files.createDirectories(home.resolve(m.group(1)));
                return 0;
            }
            if ((m = Pattern.compile("^rm -rf '([^']+)'$").matcher(line)).matches()) {
                FileTrees.delete(home.resolve(m.group(1)));
                return 0;
            }
            if ((m = Pattern.compile("^sh '([^']+)'$").matcher(line)).matches()) {
                commands.add("script: " + Files.readString(home.resolve(m.group(1))).strip());
                return execute(Files.readString(home.resolve(m.group(1))).strip());
            }
            if (line.endsWith("native-image' --version") || line.endsWith("native-image --version")) {
                print(TestSupport.MANDREL_VERSION_OUTPUT);
                return 0;
            }
            if ((m = Pattern.compile("^cd '([^']+)' && .*native-image'? @(\\S+)$").matcher(line)).matches()) {
                Path dir = home.resolve(m.group(1));
                List<String> args = Files.readAllLines(dir.resolve(m.group(2)));
                Path output = dir.resolve(args.get(args.indexOf("-o") + 1));
                if (!Files.isDirectory(output.getParent())) {
                    print("Error: Writing image to non-existent directory " + output.getParent() + "\n");
                    return 20;
                }
                Files.writeString(output, "remote executable");
                print("[1/8] Initializing...\n[8/8] Done\n");
                return exitCode;
            }
            print("unexpected command " + line + "\n");
            return 127;
        }

        private void print(String s) throws IOException {
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.flush();
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

    private NativeBuildRequest request(String client, Map<String, String> extraOptions) throws Exception {
        NativeSources sources = NativeSources.read(TestSupport.nativeSources(tmp));
        var options = new HashMap<>(Map.of(
                "client", client,
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

    @ParameterizedTest
    @MethodSource("clients")
    void uploadsBuildsDownloadsAndCleansUp(String client) throws Exception {
        NativeBuildRequest request = request(client, Map.of());
        var context = TestSupport.context(Files.createDirectories(tmp.resolve("work")));
        var result = new SshRunner().build(request, context);

        assertThat(result.executable()).hasContent("remote executable");
        assertThat(result.metadata()).containsEntry("host", "127.0.0.1");
        assertThat(context.versions()).containsExactly(TestSupport.MANDREL_VERSION_OUTPUT);
        String workDir = client.equals("openssh") ? ".quarkus-native-matrix/b1-macos-arm64/w"
                : ".quarkus-native-matrix/b1-macos-arm64";
        String build = "cd '" + workDir + "' && echo ready && exec '/opt/graalvm/bin/native-image' @native-image.matrix.args";
        // the OpenSSH client runs commands through script files
        assertThat(commands).contains(client.equals("openssh") ? "script: " + build : build);
        // remote work directory removed
        assertThat(home.resolve(".quarkus-native-matrix/b1-macos-arm64")).doesNotExist();
    }

    @ParameterizedTest
    @MethodSource("clients")
    void keepsRemoteFilesOnRequestAndReportsFailures(String client) throws Exception {
        exitCode = 3;
        NativeBuildRequest request = request(client, Map.of("keep-remote-files", "true"));
        assertThatThrownBy(() -> new SshRunner().build(request,
                TestSupport.context(Files.createDirectories(tmp.resolve("work")))))
                .hasMessageContaining("exit code 3");
        Path remote = home.resolve(".quarkus-native-matrix/b1-macos-arm64"
                + (client.equals("openssh") ? "/w" : ""));
        assertThat(remote.resolve("app-runner.jar")).exists();
        assertThat(remote.resolve("lib/main/dep.jar")).exists();
        assertThat(remote.resolve("native-image.matrix.args")).content().contains("\\Qio.netty\\E");
    }

    @ParameterizedTest
    @MethodSource("clients")
    void rejectsUnknownHostsByDefault(String client) throws Exception {
        NativeBuildRequest request = request(client, Map.of("strict-host-key-checking", "true",
                "known-hosts", tmp.resolve("empty_known_hosts").toString()));
        assertThatThrownBy(() -> new SshRunner().build(request,
                TestSupport.context(Files.createDirectories(tmp.resolve("work")))))
                .isInstanceOf(Exception.class);
        assertThat(commands).isEmpty();
    }

    @Test
    void autoPrefersOpenSshUnlessAPassphraseIsNeeded() {
        Assumptions.assumeTrue(OpenSshTransport.available());
        assertThat(SshRunner.useOpenSsh(RunnerOptions.of(Map.of("host", "h")))).isTrue();
        assertThat(SshRunner.useOpenSsh(RunnerOptions.of(Map.of("host", "h", "identity-passphrase", "x")))).isFalse();
        assertThat(SshRunner.useOpenSsh(RunnerOptions.of(Map.of("host", "h", "client", "mina")))).isFalse();
    }

    @Test
    void buildsOpenSshCommandLines() {
        var transport = new OpenSshTransport(RunnerOptions.of(Map.of("host", "mac", "port", "2222",
                "identity-file", "/k", "known-hosts", "/kh")), false, tmp, null);
        assertThat(transport.destination()).isEqualTo("mac");
        assertThat(transport.sshCommand("uname")).containsSequence("-p", "2222").containsSequence("-i", "/k")
                .contains("UserKnownHostsFile=/kh", "StrictHostKeyChecking=yes", "BatchMode=yes").endsWith("mac", "uname");
        assertThat(transport.scpCommand("a", "mac:b")).startsWith("scp", "-r", "-q").containsSequence("-P", "2222");
    }

    @Test
    void buildsRemoteCommands() {
        RunnerOptions o = RunnerOptions.of(Map.of("graalvm-home", "C:\\graalvm",
                "pre-command", "call \"C:\\VS\\vcvars64.bat\""));
        assertThat(SshRunner.windowsCommand(o, ".quarkus-native-matrix/b1-win"))
                .isEqualTo("cd /d \".quarkus-native-matrix\\b1-win\" && call \"C:\\VS\\vcvars64.bat\" &&"
                        + " \"C:\\graalvm\\bin\\native-image.cmd\" @native-image.matrix.args");
        assertThat(SshRunner.posixCommand(RunnerOptions.empty(), "it's/dir"))
                .isEqualTo("cd 'it'\"'\"'s/dir' && exec native-image @native-image.matrix.args");
        assertThat(SshRunner.posixFullCommand(RunnerOptions.of(Map.of("graalvm-home", "/opt/g")), "w", "sh ./mvnw"))
                .isEqualTo("cd 'w' && export JAVA_HOME='/opt/g' PATH='/opt/g/bin':\"$PATH\" && sh ./mvnw");
        assertThat(SshRunner.windowsFullCommand(RunnerOptions.of(Map.of("graalvm-home", "C:\\g")), "w", "call mvnw.cmd"))
                .isEqualTo("cd /d \"w\" && set \"JAVA_HOME=C:\\g\" && set \"PATH=C:\\g\\bin;%PATH%\" && call mvnw.cmd");
    }
}
