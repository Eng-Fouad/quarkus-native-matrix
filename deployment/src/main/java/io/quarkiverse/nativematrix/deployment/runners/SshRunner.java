package io.quarkiverse.nativematrix.deployment.runners;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import io.quarkiverse.nativematrix.deployment.orchestrator.NativeMatrixOrchestrator;
import io.quarkiverse.nativematrix.spi.BuildContext;
import io.quarkiverse.nativematrix.spi.BuildLog;
import io.quarkiverse.nativematrix.spi.NativeBuildException;
import io.quarkiverse.nativematrix.spi.NativeBuildRequest;
import io.quarkiverse.nativematrix.spi.NativeBuildResult;
import io.quarkiverse.nativematrix.spi.NativeBuildRunner;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.RunnerOptions;
import io.quarkiverse.nativematrix.spi.support.ArgFile;
import io.quarkiverse.nativematrix.spi.support.FileTrees;
import io.quarkiverse.nativematrix.spi.support.LineOutputStream;

/**
 * Runs {@code native-image} on any machine reachable over SSH: Linux, macOS, or Windows with OpenSSH. Nothing has to be
 * installed on the remote machine besides GraalVM/Mandrel (and, on Windows, the MSVC build tools): files are
 * transferred with SFTP and the build runs through an exec channel.
 * <p>
 * Options:
 * <ul>
 * <li>{@code host} (required), {@code port} (22), {@code user} (local user name)</li>
 * <li>{@code identity-file}: comma separated private keys; defaults to {@code ~/.ssh/id_ed25519},
 * {@code ~/.ssh/id_ecdsa}, {@code ~/.ssh/id_rsa}</li>
 * <li>{@code identity-passphrase}: passphrase of encrypted keys (use an expression such as
 * {@code ${SSH_KEY_PASSPHRASE}})</li>
 * <li>{@code known-hosts}: defaults to {@code ~/.ssh/known_hosts}; {@code strict-host-key-checking} (true) rejects
 * unknown hosts</li>
 * <li>{@code graalvm-home}: GraalVM/Mandrel installation on the remote machine; otherwise {@code native-image} must be
 * on the PATH of non-interactive sessions</li>
 * <li>{@code pre-command}: run before {@code native-image} in the same shell, e.g.
 * {@code source ~/.sdkman/bin/sdkman-init.sh} or, on Windows,
 * {@code call "C:\Program Files\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"}</li>
 * <li>{@code work-directory}: remote directory, relative to the home directory or absolute; defaults to
 * {@code .quarkus-native-matrix}</li>
 * <li>{@code keep-remote-files} (false), {@code connect-timeout} (30s)</li>
 * </ul>
 */
public class SshRunner implements NativeBuildRunner {

    public static final String ID = "ssh";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "ssh";
    }

    @Override
    public void validate(NativeBuildRequest request) {
        RunnerOptions o = request.options();
        o.require("host");
        o.getInt("port", 22);
        o.getDuration("connect-timeout", Duration.ofSeconds(30));
        o.getBoolean("strict-host-key-checking", true);
        for (Path key : identityFiles(o)) {
            if (!Files.isRegularFile(key) && o.get("identity-file").isPresent()) {
                throw new IllegalArgumentException("Identity file not found: " + key);
            }
        }
    }

    @Override
    public NativeBuildResult build(NativeBuildRequest request, BuildContext context) throws Exception {
        RunnerOptions o = request.options();
        BuildLog log = context.log();
        String host = o.require("host");
        int port = o.getInt("port", 22);
        String user = o.get("user", System.getProperty("user.name"));
        long connectTimeout = o.getDuration("connect-timeout", Duration.ofSeconds(30)).toMillis();
        boolean windows = request.platform().os() == Platform.Os.WINDOWS;
        String remoteDir = remoteDirectory(request);

        try (SshClient client = SshClient.setUpDefaultClient()) {
            if (o.getBoolean("strict-host-key-checking", true)) {
                Path knownHosts = o.get("known-hosts").map(Path::of)
                        .orElse(Path.of(System.getProperty("user.home"), ".ssh", "known_hosts"));
                client.setServerKeyVerifier(new KnownHostsServerKeyVerifier(RejectAllServerKeyVerifier.INSTANCE, knownHosts));
            } else {
                client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
            }
            List<Path> keys = identityFiles(o).stream().filter(Files::isRegularFile).toList();
            FileKeyPairProvider keyProvider = new FileKeyPairProvider(keys);
            o.get("identity-passphrase").ifPresent(p -> keyProvider.setPasswordFinder(FilePasswordProvider.of(p)));
            client.setKeyIdentityProvider(keyProvider);
            client.start();

            log.infof("Connecting to %s@%s:%d", user, host, port);
            try (ClientSession session = client.connect(user, host, port).verify(connectTimeout).getSession()) {
                session.auth().verify(connectTimeout);
                try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                    try {
                        log.info("Uploading the native sources to " + remoteDir);
                        mkdirs(sftp, remoteDir);
                        upload(sftp, request.nativeSourcesDirectory(), remoteDir, context);
                        try (OutputStream os = sftp.write(remoteDir + "/" + ArgFile.DEFAULT_NAME)) {
                            os.write(ArgFile.render(request.nativeImageArgs())
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }

                        String command = windows ? windowsCommand(o, remoteDir) : posixCommand(o, remoteDir);
                        log.debug("Remote command: " + command);
                        int exit = exec(session, command, log, context, request.timeout());
                        if (exit != 0) {
                            throw new NativeBuildException("native-image failed on " + host + " (exit code " + exit + ")");
                        }

                        log.info("Downloading the executable");
                        download(sftp, remoteDir + "/" + NativeBuildRequest.DEFAULT_OUTPUT_DIR, request.outputDirectory());
                    } finally {
                        if (!o.getBoolean("keep-remote-files", false)) {
                            boolean interrupted = Thread.interrupted();
                            try {
                                deleteRecursively(sftp, remoteDir);
                            } catch (IOException e) {
                                log.warn("Could not delete " + remoteDir + " on " + host + ": " + e.getMessage());
                            } finally {
                                if (interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                    }
                }
            }
        }
        return RunnerSupport.collectResult(request).withMetadata("host", host);
    }

    static String remoteDirectory(NativeBuildRequest request) {
        String base = request.options().get("work-directory", ".quarkus-native-matrix").replace('\\', '/');
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + request.buildId() + "-" + NativeMatrixOrchestrator.sanitize(request.targetName());
    }

    static String posixCommand(RunnerOptions o, String remoteDir) {
        String nativeImage = o.get("graalvm-home").map(h -> RunnerSupport.shellQuote(h + "/bin/native-image"))
                .orElse("native-image");
        StringBuilder sb = new StringBuilder("cd ").append(RunnerSupport.shellQuote(remoteDir)).append(" && ");
        o.get("pre-command").ifPresent(p -> sb.append(p).append(" && "));
        return sb.append("exec ").append(nativeImage).append(" @").append(ArgFile.DEFAULT_NAME).toString();
    }

    static String windowsCommand(RunnerOptions o, String remoteDir) {
        // OpenSSH for Windows runs commands with cmd.exe by default
        String nativeImage = o.get("graalvm-home").map(h -> RunnerSupport.cmdQuote(h + "\\bin\\native-image.cmd"))
                .orElse("native-image");
        StringBuilder sb = new StringBuilder("cd /d ").append(RunnerSupport.cmdQuote(remoteDir.replace('/', '\\')))
                .append(" && ");
        o.get("pre-command").ifPresent(p -> sb.append(p).append(" && "));
        return sb.append(nativeImage).append(" @").append(ArgFile.DEFAULT_NAME).toString();
    }

    private static int exec(ClientSession session, String command, BuildLog log, BuildContext context, Duration timeout)
            throws IOException, InterruptedException {
        try (ChannelExec channel = session.createExecChannel(command);
                LineOutputStream out = new LineOutputStream(log::output)) {
            channel.setOut(out);
            channel.setErr(out);
            channel.open().verify(Duration.ofSeconds(30).toMillis());
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                Set<ClientChannelEvent> events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 1000);
                if (events.contains(ClientChannelEvent.CLOSED)) {
                    break;
                }
                if (context.isCancelled() || Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
                    channel.close(true);
                    throw new InterruptedException("Remote build cancelled");
                }
            }
            Integer exit = channel.getExitStatus();
            return exit == null ? -1 : exit;
        }
    }

    static List<Path> identityFiles(RunnerOptions o) {
        List<String> configured = o.getList("identity-file");
        List<Path> paths = new ArrayList<>();
        if (configured.isEmpty()) {
            Path ssh = Path.of(System.getProperty("user.home"), ".ssh");
            for (String name : new String[] { "id_ed25519", "id_ecdsa", "id_rsa" }) {
                paths.add(ssh.resolve(name));
            }
        } else {
            for (String c : configured) {
                paths.add(Path.of(c.startsWith("~/") ? System.getProperty("user.home") + c.substring(1) : c));
            }
        }
        return paths;
    }

    private static void mkdirs(SftpClient sftp, String dir) throws IOException {
        String[] parts = dir.split("/");
        StringBuilder current = new StringBuilder(dir.startsWith("/") ? "/" : "");
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (current.length() > 0 && current.charAt(current.length() - 1) != '/') {
                current.append('/');
            }
            current.append(part);
            if (!exists(sftp, current.toString())) {
                sftp.mkdir(current.toString());
            }
        }
    }

    private static boolean exists(SftpClient sftp, String path) throws IOException {
        try {
            sftp.stat(path);
            return true;
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) {
                return false;
            }
            throw e;
        }
    }

    private static void upload(SftpClient sftp, Path localDir, String remoteDir, BuildContext context)
            throws IOException, InterruptedException {
        for (Path file : FileTrees.listFiles(localDir)) {
            context.checkCancelled();
            String relative = localDir.relativize(file).toString().replace('\\', '/');
            String target = remoteDir + "/" + relative;
            int slash = target.lastIndexOf('/');
            mkdirs(sftp, target.substring(0, slash));
            try (OutputStream os = sftp.write(target)) {
                Files.copy(file, os);
            }
        }
    }

    private static void download(SftpClient sftp, String remoteDir, Path localDir) throws IOException {
        Files.createDirectories(localDir);
        for (SftpClient.DirEntry entry : sftp.readDir(remoteDir)) {
            String name = entry.getFilename();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            String remote = remoteDir + "/" + name;
            if (entry.getAttributes().isDirectory()) {
                download(sftp, remote, localDir.resolve(name));
            } else {
                try (InputStream in = sftp.read(remote)) {
                    Files.copy(in, localDir.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(SftpClient sftp, String path) throws IOException {
        if (!exists(sftp, path)) {
            return;
        }
        for (SftpClient.DirEntry entry : sftp.readDir(path)) {
            String name = entry.getFilename();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            String child = path + "/" + name;
            if (entry.getAttributes().isDirectory()) {
                deleteRecursively(sftp, child);
            } else {
                sftp.remove(child);
            }
        }
        sftp.rmdir(path);
    }
}
