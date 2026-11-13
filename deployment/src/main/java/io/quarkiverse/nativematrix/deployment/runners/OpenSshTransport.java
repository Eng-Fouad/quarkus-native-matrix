package io.quarkiverse.nativematrix.deployment.runners;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import io.quarkiverse.nativematrix.spi.BuildContext;
import io.quarkiverse.nativematrix.spi.BuildLog;
import io.quarkiverse.nativematrix.spi.RunnerOptions;
import io.quarkiverse.nativematrix.spi.support.FileTrees;
import io.quarkiverse.nativematrix.spi.support.Processes;

/**
 * Uses the {@code ssh} and {@code scp} executables of the build machine, so everything the user configured for OpenSSH
 * applies: SSH agent (including the Windows OpenSSH agent and 1Password/Bitwarden agents), {@code ~/.ssh/config} host
 * aliases, {@code ProxyJump}, certificates, hardware keys...
 * <p>
 * Commands are written to script files and executed by path, so that no quoting has to survive the local command line,
 * {@code ssh} and the remote shell.
 */
final class OpenSshTransport implements SshTransport {

    private final RunnerOptions options;
    private final boolean remoteWindows;
    private final Path localScratch;
    private final BuildLog log;
    private final AtomicInteger scripts = new AtomicInteger();

    OpenSshTransport(RunnerOptions options, boolean remoteWindows, Path localScratch, BuildLog log) {
        this.options = options;
        this.remoteWindows = remoteWindows;
        this.localScratch = localScratch;
        this.log = log;
        try {
            // holds the throwaway known_hosts file and the uploaded scripts
            Files.createDirectories(localScratch);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static boolean available() {
        return Processes.findExecutable("ssh", List.of()).isPresent()
                && Processes.findExecutable("scp", List.of()).isPresent();
    }

    /**
     * {@code [user@]host}; without {@code user} the OpenSSH configuration decides.
     */
    String destination() {
        String host = options.require("host");
        return options.get("user").map(u -> u + "@" + host).orElse(host);
    }

    /**
     * Options shared by {@code ssh} and {@code scp}.
     */
    List<String> commonOptions() {
        List<String> args = new ArrayList<>();
        args.addAll(List.of("-o", "BatchMode=yes"));
        args.addAll(List.of("-o", "ConnectTimeout="
                + options.getDuration("connect-timeout", Duration.ofSeconds(30)).toSeconds()));
        args.addAll(List.of("-o", "LogLevel=ERROR"));
        for (String key : options.getList("identity-file")) {
            args.addAll(List.of("-i", key));
        }
        options.get("known-hosts").ifPresent(k -> args.addAll(List.of("-o", "UserKnownHostsFile=" + k)));
        if (!options.getBoolean("strict-host-key-checking", true)) {
            args.addAll(List.of("-o", "StrictHostKeyChecking=no"));
            if (options.get("known-hosts").isEmpty()) {
                // a throwaway file: Windows OpenSSH does not treat "NUL" as the null device but creates a file
                args.addAll(List.of("-o", "UserKnownHostsFile=" + localScratch.resolve("known_hosts").toAbsolutePath()));
            }
        } else {
            args.addAll(List.of("-o", "StrictHostKeyChecking=yes"));
        }
        return args;
    }

    List<String> sshCommand(String remoteCommand) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ssh");
        cmd.addAll(commonOptions());
        options.get("port").ifPresent(p -> cmd.addAll(List.of("-p", p)));
        cmd.add(destination());
        cmd.add(remoteCommand);
        return cmd;
    }

    List<String> scpCommand(String from, String to) {
        List<String> cmd = new ArrayList<>();
        cmd.addAll(List.of("scp", "-r", "-q"));
        cmd.addAll(commonOptions());
        options.get("port").ifPresent(p -> cmd.addAll(List.of("-P", p)));
        cmd.add(from);
        cmd.add(to);
        return cmd;
    }

    private void check(List<String> command, String what) throws IOException, InterruptedException {
        Processes.Result r = Processes.capture(command, null);
        if (!r.succeeded()) {
            throw new IOException("Failed to " + what + " (exit code " + r.exitCode() + "): " + r.outputAsString());
        }
    }

    @Override
    public void mkdirs(String remoteDir) throws IOException, InterruptedException {
        String command = remoteWindows
                ? "if not exist " + windowsPath(remoteDir) + " mkdir " + windowsPath(remoteDir)
                : "mkdir -p " + RunnerSupport.shellQuote(remoteDir);
        check(sshCommand(command), "create " + remoteDir);
    }

    @Override
    public void upload(Path localDir, String remoteDir, BuildContext context) throws IOException, InterruptedException {
        // the destination does not exist: scp -r creates it with the content of localDir
        check(scpCommand(localDir.toAbsolutePath().toString(), destination() + ":" + remoteDir), "upload " + localDir);
    }

    @Override
    public void write(String remoteFile, byte[] content) throws IOException, InterruptedException {
        Path local = Files.createDirectories(localScratch).resolve("upload-" + scripts.incrementAndGet());
        Files.write(local, content);
        check(scpCommand(local.toAbsolutePath().toString(), destination() + ":" + remoteFile), "upload " + remoteFile);
    }

    @Override
    public int run(String command, String scratchDir, Consumer<String> output, BuildContext context, Duration timeout)
            throws IOException, InterruptedException {
        int n = scripts.incrementAndGet();
        String remoteCommand;
        if (remoteWindows) {
            String script = scratchDir + "/run-" + n + ".cmd";
            write(script, ("@echo off\r\n" + command + "\r\nexit /b %errorlevel%\r\n").getBytes(StandardCharsets.UTF_8));
            remoteCommand = "cmd /c " + windowsPath(script);
        } else {
            String script = scratchDir + "/run-" + n + ".sh";
            write(script, (command + "\n").getBytes(StandardCharsets.UTF_8));
            remoteCommand = "sh " + RunnerSupport.shellQuote(script);
        }
        Processes.Result r = Processes.run(sshCommand(remoteCommand), null, Map.of(), new LineLog(output, log));
        return r.exitCode();
    }

    @Override
    public void download(String remoteDir, Path localDir) throws IOException, InterruptedException {
        Path temp = localScratch.resolve("download-" + scripts.incrementAndGet());
        check(scpCommand(destination() + ":" + remoteDir, temp.toAbsolutePath().toString()), "download " + remoteDir);
        FileTrees.copy(temp, localDir);
        FileTrees.deleteQuietly(temp);
    }

    @Override
    public void delete(String remoteDir) throws IOException, InterruptedException {
        String command = remoteWindows
                ? "if exist " + windowsPath(remoteDir) + " rmdir /s /q " + windowsPath(remoteDir)
                : "rm -rf " + RunnerSupport.shellQuote(remoteDir);
        check(sshCommand(command), "delete " + remoteDir);
    }

    private static String windowsPath(String path) {
        if (path.contains(" ")) {
            throw new IllegalArgumentException("Remote Windows paths cannot contain spaces with client=openssh: " + path);
        }
        return path.replace('/', '\\');
    }

    @Override
    public void close() {
    }

    /** Routes process output lines to the consumer, other messages to the log. */
    private record LineLog(Consumer<String> output, BuildLog log) implements BuildLog {
        @Override
        public void debug(String message) {
            log.debug(message);
        }

        @Override
        public void info(String message) {
            log.info(message);
        }

        @Override
        public void warn(String message) {
            log.warn(message);
        }

        @Override
        public void error(String message) {
            log.error(message);
        }

        @Override
        public void output(String line) {
            output.accept(line);
        }
    }
}
