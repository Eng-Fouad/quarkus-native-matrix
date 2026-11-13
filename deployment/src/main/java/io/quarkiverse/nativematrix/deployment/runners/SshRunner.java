package io.quarkiverse.nativematrix.deployment.runners;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.quarkiverse.nativematrix.deployment.orchestrator.NativeMatrixOrchestrator;
import io.quarkiverse.nativematrix.spi.BuildContext;
import io.quarkiverse.nativematrix.spi.BuildLog;
import io.quarkiverse.nativematrix.spi.BuildMode;
import io.quarkiverse.nativematrix.spi.NativeBuildException;
import io.quarkiverse.nativematrix.spi.NativeBuildRequest;
import io.quarkiverse.nativematrix.spi.NativeBuildResult;
import io.quarkiverse.nativematrix.spi.NativeBuildRunner;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.RunnerOptions;
import io.quarkiverse.nativematrix.spi.support.ArgFile;

/**
 * Runs {@code native-image} on any machine reachable over SSH: Linux, macOS, or Windows with OpenSSH. Nothing has to be
 * installed on the remote machine besides GraalVM/Mandrel (and, on Windows, the MSVC build tools).
 * <p>
 * Two SSH clients are available ({@code client} option):
 * <ul>
 * <li>{@code openssh}: the {@code ssh}/{@code scp} executables of the build machine. Everything configured for OpenSSH
 * applies: SSH agent, {@code ~/.ssh/config} aliases, {@code ProxyJump}, certificates, hardware keys.</li>
 * <li>{@code mina}: an embedded client (Apache MINA SSHD) that needs no executable but only reads key files.</li>
 * <li>{@code auto} (default): {@code openssh} when {@code ssh} and {@code scp} are on the PATH and no
 * {@code identity-passphrase} is configured, else {@code mina}.</li>
 * </ul>
 * Options:
 * <ul>
 * <li>{@code host} (required): host name, or a host alias of {@code ~/.ssh/config} with {@code openssh}</li>
 * <li>{@code port} (22), {@code user} (the local user with {@code mina}, the OpenSSH configuration with
 * {@code openssh})</li>
 * <li>{@code identity-file}: comma separated private keys; {@code mina} defaults to {@code ~/.ssh/id_ed25519},
 * {@code ~/.ssh/id_ecdsa}, {@code ~/.ssh/id_rsa}</li>
 * <li>{@code identity-passphrase} ({@code mina} only): passphrase of encrypted keys, e.g. {@code ${SSH_KEY_PASSPHRASE}}</li>
 * <li>{@code known-hosts}: defaults to {@code ~/.ssh/known_hosts}; {@code strict-host-key-checking} (true) rejects
 * unknown hosts</li>
 * <li>{@code graalvm-home}: GraalVM/Mandrel installation on the remote machine; otherwise {@code native-image} must be
 * on the PATH of non-interactive sessions</li>
 * <li>{@code pre-command}: run before {@code native-image} in the same shell, e.g.
 * {@code source ~/.sdkman/bin/sdkman-init.sh} or, on Windows,
 * {@code call "C:\Program Files\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"}</li>
 * <li>{@code work-directory}: remote directory, relative to the home directory or absolute; defaults to
 * {@code .quarkus-native-matrix}</li>
 * <li>{@code keep-remote-files} (false), {@code connect-timeout} (30s), {@code check-version} (true)</li>
 * </ul>
 * <p>
 * In {@code mode=full}, the project is uploaded and built remotely with its build tool; {@code graalvm-home}, when set,
 * becomes {@code JAVA_HOME} and is prepended to the {@code PATH}. The remote machine then also needs the build tool
 * (or its wrapper) and access to the artifact repositories.
 */
public class SshRunner implements NativeBuildRunner {

    public static final String ID = "ssh";
    static final String WORK_SUBDIRECTORY = "w";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "ssh";
    }

    @Override
    public boolean supports(BuildMode mode) {
        return true;
    }

    @Override
    public void validate(NativeBuildRequest request) {
        RunnerOptions o = request.options();
        o.require("host");
        o.getInt("port", 22);
        o.getDuration("connect-timeout", Duration.ofSeconds(30));
        o.getBoolean("strict-host-key-checking", true);
        String client = o.get("client", "auto");
        if (!List.of("auto", "openssh", "mina").contains(client)) {
            throw new IllegalArgumentException("Option 'client' must be auto, openssh or mina, got '" + client + "'");
        }
        if (client.equals("openssh") && !OpenSshTransport.available()) {
            throw new IllegalArgumentException("client=openssh but ssh/scp are not on the PATH");
        }
        if (client.equals("openssh") && o.get("identity-passphrase").isPresent()) {
            throw new IllegalArgumentException("identity-passphrase is not supported with client=openssh: load the key"
                    + " in your SSH agent instead");
        }
        for (Path key : identityFiles(o)) {
            if (!Files.isRegularFile(key) && o.get("identity-file").isPresent()) {
                throw new IllegalArgumentException("Identity file not found: " + key);
            }
        }
    }

    static boolean useOpenSsh(RunnerOptions o) {
        return switch (o.get("client", "auto")) {
            case "openssh" -> true;
            case "mina" -> false;
            default -> o.get("identity-passphrase").isEmpty() && OpenSshTransport.available();
        };
    }

    @Override
    public NativeBuildResult build(NativeBuildRequest request, BuildContext context) throws Exception {
        RunnerOptions o = request.options();
        BuildLog log = context.log();
        String host = o.require("host");
        boolean windows = request.platform().os() == Platform.Os.WINDOWS;
        boolean openssh = useOpenSsh(o);
        String remoteDir = remoteDirectory(request);
        // the OpenSSH client copies directories with scp -r, which needs a destination that does not exist yet
        String workDir = openssh ? remoteDir + "/" + WORK_SUBDIRECTORY : remoteDir;
        boolean full = request.mode() == BuildMode.FULL;

        try (SshTransport transport = openssh
                ? new OpenSshTransport(o, windows, context.workDirectory().resolve("ssh"), log)
                : MinaSshTransport.connect(o, log)) {
            if (openssh) {
                log.info("Connecting to " + host + " with OpenSSH");
            }
            try {
                transport.mkdirs(remoteDir);
                if (o.getBoolean("check-version", true)) {
                    StringBuilder version = new StringBuilder();
                    int exit = transport.run(windows ? windowsVersionCommand(o) : posixVersionCommand(o), remoteDir,
                            line -> version.append(line).append('\n'), context, Duration.ofMinutes(2));
                    if (exit == 0) {
                        context.reportNativeImageVersion(version.toString());
                    } else {
                        log.warn("native-image --version failed on " + host + " (exit code " + exit + "): " + version);
                    }
                }

                String command;
                if (full) {
                    log.info("Uploading the project to " + workDir);
                    Path project = request.requireFullBuild().stage(context.workDirectory().resolve("project"),
                            request.platform());
                    transport.upload(project, workDir, context);
                    String script = request.requireFullBuild().script(request.platform(), request.outputName());
                    command = windows ? windowsFullCommand(o, workDir, script) : posixFullCommand(o, workDir, script);
                } else {
                    log.info("Uploading the native sources to " + workDir);
                    transport.upload(request.nativeSourcesDirectory(), workDir, context);
                    transport.write(workDir + "/" + ArgFile.DEFAULT_NAME,
                            ArgFile.render(request.nativeImageArgs()).getBytes(StandardCharsets.UTF_8));
                    // native-image does not create the directory of its -o path
                    transport.mkdirs(workDir + "/" + NativeBuildRequest.DEFAULT_OUTPUT_DIR);
                    command = windows ? windowsCommand(o, workDir) : posixCommand(o, workDir);
                }

                log.debug("Remote command: " + command);
                int exit = transport.run(command, remoteDir, log::output, context, request.timeout());
                if (exit != 0) {
                    throw new NativeBuildException((full ? "The project build" : "native-image") + " failed on "
                            + host + " (exit code " + exit + ")");
                }

                log.info("Downloading the executable");
                transport.download(workDir + "/" + NativeBuildRequest.DEFAULT_OUTPUT_DIR, request.outputDirectory());
            } finally {
                if (!o.getBoolean("keep-remote-files", false)) {
                    boolean interrupted = Thread.interrupted();
                    try {
                        transport.delete(remoteDir);
                    } catch (IOException | InterruptedException e) {
                        log.warn("Could not delete " + remoteDir + " on " + host + ": " + e.getMessage());
                    } finally {
                        if (interrupted) {
                            Thread.currentThread().interrupt();
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

    static String posixFullCommand(RunnerOptions o, String remoteDir, String script) {
        StringBuilder sb = new StringBuilder("cd ").append(RunnerSupport.shellQuote(remoteDir)).append(" && ");
        o.get("graalvm-home").ifPresent(h -> sb.append("export JAVA_HOME=").append(RunnerSupport.shellQuote(h))
                .append(" PATH=").append(RunnerSupport.shellQuote(h + "/bin")).append(":\"$PATH\" && "));
        o.get("pre-command").ifPresent(p -> sb.append(p).append(" && "));
        return sb.append(script).toString();
    }

    static String windowsFullCommand(RunnerOptions o, String remoteDir, String script) {
        StringBuilder sb = new StringBuilder("cd /d ").append(RunnerSupport.cmdQuote(remoteDir.replace('/', '\\')))
                .append(" && ");
        o.get("graalvm-home").ifPresent(h -> sb.append("set \"JAVA_HOME=").append(h).append("\" && set \"PATH=")
                .append(h).append("\\bin;%PATH%\" && "));
        o.get("pre-command").ifPresent(p -> sb.append(p).append(" && "));
        return sb.append(script).toString();
    }

    static String posixVersionCommand(RunnerOptions o) {
        StringBuilder sb = new StringBuilder();
        o.get("pre-command").ifPresent(p -> sb.append(p).append(" && "));
        return sb.append(o.get("graalvm-home").map(h -> RunnerSupport.shellQuote(h + "/bin/native-image"))
                .orElse("native-image")).append(" --version").toString();
    }

    static String windowsVersionCommand(RunnerOptions o) {
        StringBuilder sb = new StringBuilder();
        o.get("pre-command").ifPresent(p -> sb.append(p).append(" && "));
        return sb.append(o.get("graalvm-home").map(h -> RunnerSupport.cmdQuote(h + "\\bin\\native-image.cmd"))
                .orElse("native-image")).append(" --version").toString();
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
}
