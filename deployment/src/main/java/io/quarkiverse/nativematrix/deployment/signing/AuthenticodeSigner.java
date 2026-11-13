package io.quarkiverse.nativematrix.deployment.signing;

import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Collections;
import java.util.Optional;

import io.quarkiverse.nativematrix.spi.BuildLog;
import io.quarkiverse.nativematrix.spi.NativeBuildException;
import io.quarkiverse.nativematrix.spi.Platform;
import net.jsign.DigestAlgorithm;
import net.jsign.KeyStoreBuilder;
import net.jsign.Signable;

/**
 * Authenticode signing of Windows executables with <a href="https://ebourg.github.io/jsign/">Jsign</a>, in-process, on
 * any OS. Supports every Jsign key store: PKCS#12/JKS files, PKCS#11 tokens, Azure Trusted Signing, Azure Key Vault,
 * AWS KMS, Google Cloud KMS, HashiCorp Vault, DigiCert ONE, SSL.com eSigner...
 *
 * @param keystore the key store file, or the store name/URL for cloud key stores
 * @param storetype a Jsign store type, e.g. {@code PKCS12}, {@code AZURETRUSTEDSIGNING}, {@code AWS}
 * @param storepass the key store password (or cloud credentials, as expected by Jsign)
 * @param alias the key alias; the first key of the store when absent
 * @param keypass the key password, defaults to the store password
 * @param certfile the certificate chain, for stores that do not hold it (cloud key services)
 * @param timestamp whether to timestamp the signature (recommended: the signature stays valid after the certificate
 *        expires)
 * @param tsaurl the timestamping authority
 * @param programName the program name shown by Windows
 * @param programUrl the program URL
 */
public record AuthenticodeSigner(String keystore, String storetype, Optional<String> storepass, Optional<String> alias,
        Optional<String> keypass, Optional<String> certfile, boolean timestamp, String tsaurl, Optional<String> programName,
        Optional<String> programUrl) implements ExecutableSigner {

    @Override
    public String name() {
        return "authenticode";
    }

    @Override
    public boolean appliesTo(Platform platform) {
        return platform.os() == Platform.Os.WINDOWS;
    }

    @Override
    public String fingerprint() {
        return "authenticode " + storetype + " " + keystore + " " + alias.orElse("") + " " + certfile.orElse("") + " "
                + (timestamp ? tsaurl : "no-timestamp") + " " + programName.orElse("") + " " + programUrl.orElse("");
    }

    /**
     * Opens the key store, to report configuration errors before any build starts.
     */
    public KeyStore loadKeyStore() throws Exception {
        KeyStoreBuilder builder = new KeyStoreBuilder().storetype(storetype).keystore(keystore);
        storepass.ifPresent(builder::storepass);
        keypass.ifPresent(builder::keypass);
        certfile.ifPresent(builder::certfile);
        return builder.build();
    }

    @Override
    public void sign(Path executable, Platform platform, BuildLog log, Path workDirectory) throws Exception {
        KeyStore store = loadKeyStore();
        String keyAlias = alias.orElseGet(() -> {
            try {
                return Collections.list(store.aliases()).stream().findFirst().orElse(null);
            } catch (Exception e) {
                return null;
            }
        });
        if (keyAlias == null) {
            throw new NativeBuildException("No key in the Authenticode key store " + keystore);
        }
        net.jsign.AuthenticodeSigner signer = new net.jsign.AuthenticodeSigner(store, keyAlias,
                keypass.or(() -> storepass).orElse(null))
                .withDigestAlgorithm(DigestAlgorithm.SHA256)
                .withSignaturesReplaced(true)
                .withTimestamping(timestamp);
        if (timestamp) {
            signer.withTimestampingAuthority(tsaurl);
        }
        programName.ifPresent(signer::withProgramName);
        programUrl.ifPresent(signer::withProgramURL);
        try (Signable file = Signable.of(executable.toFile())) {
            signer.sign(file);
        }
        log.info("Signed " + executable.getFileName() + " (Authenticode, key " + keyAlias + ")");
    }
}
