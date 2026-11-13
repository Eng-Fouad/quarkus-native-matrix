package io.quarkiverse.nativematrix.deployment.signing;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.quarkiverse.nativematrix.spi.BuildLog;
import io.quarkiverse.nativematrix.spi.NativeBuildException;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.support.Processes;

/**
 * Signs (and optionally notarizes) macOS executables with
 * <a href="https://gregoryszorc.com/docs/apple-codesign/stable/">rcodesign</a> (apple-codesign), which runs on Linux,
 * Windows and macOS: no Mac or keychain is needed on the build machine.
 * <p>
 * The executable is signed with the hardened runtime, as notarization requires. Apple notarizes archives, not bare
 * executables, and a notarization ticket cannot be stapled to a bare executable: Gatekeeper checks it online.
 *
 * @param command the rcodesign command, e.g. {@code rcodesign} or a path
 * @param p12File the Developer ID Application certificate and key, as a PKCS#12 file
 * @param p12Password its password
 * @param entitlements an entitlements plist, if the application needs some
 * @param notarize whether to notarize the signed executable
 * @param apiKeyFile the App Store Connect API key, as written by {@code rcodesign encode-app-store-connect-api-key}
 */
public record AppleSigner(List<String> command, String p12File, Optional<String> p12Password,
        Optional<String> entitlements, boolean notarize, Optional<String> apiKeyFile) implements ExecutableSigner {

    @Override
    public String name() {
        return notarize ? "apple-notarized" : "apple";
    }

    @Override
    public boolean appliesTo(Platform platform) {
        return platform.os() == Platform.Os.MACOS;
    }

    @Override
    public String fingerprint() {
        return "apple " + p12File + " " + entitlements.orElse("") + " " + notarize;
    }

    public void validate() {
        if (!Files.isRegularFile(Path.of(p12File))) {
            throw new IllegalArgumentException("macOS signing certificate not found: " + p12File);
        }
        if (notarize && apiKeyFile.isEmpty()) {
            throw new IllegalArgumentException("Notarization needs quarkus.native-matrix.signing.macos.api-key-file");
        }
        if (command.size() == 1 && Processes.findExecutable(command.get(0), List.of()).isEmpty()
                && !Path.of(command.get(0)).isAbsolute()) {
            throw new IllegalArgumentException("rcodesign not found in the PATH; install apple-codesign or set"
                    + " quarkus.native-matrix.signing.macos.command");
        }
    }

    @Override
    public void sign(Path executable, Platform platform, BuildLog log, Path workDirectory) throws Exception {
        Files.createDirectories(workDirectory);
        List<String> sign = new ArrayList<>(command);
        sign.addAll(List.of("sign", "--p12-file", p12File, "--code-signature-flags", "runtime"));
        if (p12Password.isPresent()) {
            // never on the command line: other processes could read it
            Path passwordFile = workDirectory.resolve("p12-password");
            Files.writeString(passwordFile, p12Password.get(), StandardCharsets.UTF_8);
            sign.addAll(List.of("--p12-password-file", passwordFile.toString()));
        }
        entitlements.ifPresent(e -> sign.addAll(List.of("--entitlements-xml-path", e)));
        sign.add(executable.toString());
        try {
            run(sign, log, "rcodesign sign");
        } finally {
            Files.deleteIfExists(workDirectory.resolve("p12-password"));
        }
        log.info("Signed " + executable.getFileName() + " (Developer ID, hardened runtime)");

        if (notarize) {
            Path zip = workDirectory.resolve(executable.getFileName() + ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
                zos.putNextEntry(new ZipEntry(executable.getFileName().toString()));
                Files.copy(executable, zos);
                zos.closeEntry();
            }
            log.info("Submitting to Apple for notarization (this can take several minutes)");
            List<String> submit = new ArrayList<>(command);
            submit.addAll(List.of("notary-submit", "--api-key-file", apiKeyFile.orElseThrow(), "--wait",
                    zip.toString()));
            run(submit, log, "rcodesign notary-submit");
            log.info("Notarized " + executable.getFileName());
        }
    }

    private static void run(List<String> cmd, BuildLog log, String what) throws Exception {
        Processes.Result r = Processes.run(cmd, null, Map.of(), log);
        if (!r.succeeded()) {
            throw new NativeBuildException(what + " failed with exit code " + r.exitCode());
        }
    }
}
