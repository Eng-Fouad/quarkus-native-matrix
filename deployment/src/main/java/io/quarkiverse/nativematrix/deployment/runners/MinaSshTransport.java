package io.quarkiverse.nativematrix.deployment.runners;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

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

import io.quarkiverse.nativematrix.spi.BuildContext;
import io.quarkiverse.nativematrix.spi.BuildLog;
import io.quarkiverse.nativematrix.spi.RunnerOptions;
import io.quarkiverse.nativematrix.spi.support.FileTrees;
import io.quarkiverse.nativematrix.spi.support.LineOutputStream;

/**
 * Embedded SSH client (Apache MINA SSHD): SFTP transfers and exec channels. Authenticates with key files only.
 */
final class MinaSshTransport implements SshTransport {

    private final SshClient client;
    private final ClientSession session;
    private final SftpClient sftp;

    private MinaSshTransport(SshClient client, ClientSession session, SftpClient sftp) {
        this.client = client;
        this.session = session;
        this.sftp = sftp;
    }

    static MinaSshTransport connect(RunnerOptions o, BuildLog log) throws IOException {
        String host = o.require("host");
        int port = o.getInt("port", 22);
        String user = o.get("user", System.getProperty("user.name"));
        long connectTimeout = o.getDuration("connect-timeout", Duration.ofSeconds(30)).toMillis();
        SshClient client = SshClient.setUpDefaultClient();
        try {
            if (o.getBoolean("strict-host-key-checking", true)) {
                Path knownHosts = o.get("known-hosts").map(Path::of)
                        .orElse(Path.of(System.getProperty("user.home"), ".ssh", "known_hosts"));
                client.setServerKeyVerifier(new KnownHostsServerKeyVerifier(RejectAllServerKeyVerifier.INSTANCE, knownHosts));
            } else {
                client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
            }
            List<Path> keys = SshRunner.identityFiles(o).stream().filter(Files::isRegularFile).toList();
            FileKeyPairProvider keyProvider = new FileKeyPairProvider(keys);
            o.get("identity-passphrase").ifPresent(p -> keyProvider.setPasswordFinder(FilePasswordProvider.of(p)));
            client.setKeyIdentityProvider(keyProvider);
            client.start();
            log.infof("Connecting to %s@%s:%d", user, host, port);
            ClientSession session = client.connect(user, host, port).verify(connectTimeout).getSession();
            try {
                session.auth().verify(connectTimeout);
                return new MinaSshTransport(client, session, SftpClientFactory.instance().createSftpClient(session));
            } catch (IOException | RuntimeException e) {
                session.close();
                throw e;
            }
        } catch (IOException | RuntimeException e) {
            client.close();
            throw e;
        }
    }

    @Override
    public void mkdirs(String dir) throws IOException {
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
            if (!exists(current.toString())) {
                sftp.mkdir(current.toString());
            }
        }
    }

    @Override
    public void upload(Path localDir, String remoteDir, BuildContext context) throws IOException, InterruptedException {
        mkdirs(remoteDir);
        for (Path file : FileTrees.listFiles(localDir)) {
            context.checkCancelled();
            String relative = localDir.relativize(file).toString().replace('\\', '/');
            String target = remoteDir + "/" + relative;
            mkdirs(target.substring(0, target.lastIndexOf('/')));
            try (OutputStream os = sftp.write(target)) {
                Files.copy(file, os);
            }
        }
    }

    @Override
    public void write(String remoteFile, byte[] content) throws IOException {
        try (OutputStream os = sftp.write(remoteFile)) {
            os.write(content);
        }
    }

    @Override
    public int run(String command, String scratchDir, Consumer<String> output, BuildContext context, Duration timeout)
            throws IOException, InterruptedException {
        try (ChannelExec channel = session.createExecChannel(command);
                LineOutputStream out = new LineOutputStream(output)) {
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
                    throw new InterruptedException("Remote command cancelled");
                }
            }
            Integer exit = channel.getExitStatus();
            return exit == null ? -1 : exit;
        }
    }

    @Override
    public void download(String remoteDir, Path localDir) throws IOException {
        Files.createDirectories(localDir);
        for (SftpClient.DirEntry entry : sftp.readDir(remoteDir)) {
            String name = entry.getFilename();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            String remote = remoteDir + "/" + name;
            if (entry.getAttributes().isDirectory()) {
                download(remote, localDir.resolve(name));
            } else {
                try (InputStream in = sftp.read(remote)) {
                    Files.copy(in, localDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    @Override
    public void delete(String path) throws IOException {
        if (!exists(path)) {
            return;
        }
        for (SftpClient.DirEntry entry : sftp.readDir(path)) {
            String name = entry.getFilename();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            String child = path + "/" + name;
            if (entry.getAttributes().isDirectory()) {
                delete(child);
            } else {
                sftp.remove(child);
            }
        }
        sftp.rmdir(path);
    }

    private boolean exists(String path) throws IOException {
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

    @Override
    public void close() throws IOException {
        try {
            sftp.close();
        } finally {
            try {
                session.close();
            } finally {
                client.close();
            }
        }
    }
}
