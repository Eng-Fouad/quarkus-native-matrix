package io.quarkiverse.nativematrix.deployment.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.bouncycastle.cms.CMSSignedData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.nativematrix.deployment.TestSupport;
import io.quarkiverse.nativematrix.deployment.orchestrator.MatrixTarget;
import io.quarkiverse.nativematrix.deployment.orchestrator.NativeMatrixOrchestrator;
import io.quarkiverse.nativematrix.deployment.orchestrator.NativeSources;
import io.quarkiverse.nativematrix.deployment.orchestrator.RunnerRegistry;
import io.quarkiverse.nativematrix.deployment.orchestrator.TargetResult;
import io.quarkiverse.nativematrix.spi.BuildContext;
import io.quarkiverse.nativematrix.spi.BuildLog;
import io.quarkiverse.nativematrix.spi.NativeBuildRequest;
import io.quarkiverse.nativematrix.spi.NativeBuildResult;
import io.quarkiverse.nativematrix.spi.NativeBuildRunner;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.RunnerOptions;
import io.quarkiverse.nativematrix.spi.support.Processes;
import net.jsign.Signable;

class SigningTest {

    @TempDir
    Path tmp;

    private Path keystore() throws Exception {
        Path ks = tmp.resolve("signing.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Processes.captureOrFail(List.of(keytool.toString(), "-genkeypair", "-alias", "matrix", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "2", "-dname", "CN=Native Matrix Test", "-storetype", "PKCS12",
                "-keystore", ks.toString(), "-storepass", "changeit", "-keypass", "changeit"), null);
        return ks;
    }

    private AuthenticodeSigner authenticode(Path ks, String password) {
        return new AuthenticodeSigner(ks.toString(), "PKCS12", Optional.of(password), Optional.empty(), Optional.empty(),
                Optional.empty(), false, "http://timestamp.digicert.com", Optional.of("Hello Matrix"),
                Optional.of("https://example.org"));
    }

    @Test
    void signsWindowsExecutablesWithAuthenticode() throws Exception {
        // a real PE file: the java launcher of the JDK running the tests (Windows only)
        Path launcher = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Assumptions.assumeTrue(Files.isRegularFile(launcher), "needs a Windows PE executable");
        Path exe = Files.copy(launcher, tmp.resolve("app-runner.exe"));
        AuthenticodeSigner signer = authenticode(keystore(), "changeit");

        assertThat(signer.appliesTo(Platform.parse("windows-arm64"))).isTrue();
        assertThat(signer.appliesTo(Platform.parse("linux-amd64"))).isFalse();
        assertThat(signer.fingerprint()).doesNotContain("changeit");
        signer.sign(exe, Platform.parse("windows-amd64"), BuildLog.console("test"), tmp.resolve("work"));

        try (Signable signed = Signable.of(exe.toFile())) {
            List<CMSSignedData> signatures = signed.getSignatures();
            assertThat(signatures).hasSize(1); // the JDK signature was replaced
            CMSSignedData signature = signatures.get(0);
            // self-signed test certificate: the issuer of the signer is the test subject
            var signerId = signature.getSignerInfos().getSigners().iterator().next().getSID();
            assertThat(signerId.getIssuer().toString()).isEqualTo("CN=Native Matrix Test");
        }
    }

    @Test
    void reportsUnusableKeyStores() throws Exception {
        assertThatThrownBy(() -> authenticode(keystore(), "wrong").loadKeyStore()).isInstanceOf(Exception.class);
    }

    @Test
    void signsAndNotarizesMacExecutablesWithRcodesign() throws Exception {
        Path log = tmp.resolve("rcodesign.log");
        Path source = Path.of(getClass().getResource("/fake-tools/RecordArgs.java").toURI());
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path p12 = Files.writeString(tmp.resolve("developer-id.p12"), "p12");
        Path apiKey = Files.writeString(tmp.resolve("api-key.json"), "{}");
        Path exe = Files.writeString(tmp.resolve("app-runner"), "mach-o");
        AppleSigner signer = new AppleSigner(List.of(java.toString(), source.toString(), log.toString()), p12.toString(),
                Optional.of("s3cret"), Optional.empty(), true, Optional.of(apiKey.toString()));
        signer.validate();
        assertThat(signer.fingerprint()).doesNotContain("s3cret");

        Path work = tmp.resolve("work");
        signer.sign(exe, Platform.parse("macos-arm64"), BuildLog.console("test"), work);

        List<String> calls = Files.readAllLines(log);
        assertThat(calls.get(0)).startsWith("sign --p12-file " + p12 + " --code-signature-flags runtime --p12-password-file")
                .endsWith(exe + " [password=s3cret]");
        assertThat(calls.get(1)).startsWith("notary-submit --api-key-file " + apiKey + " --wait")
                .endsWith("app-runner.zip");
        // the password file does not outlive the signing
        assertThat(work.resolve("p12-password")).doesNotExist();

        assertThatThrownBy(() -> new AppleSigner(List.of("rcodesign"), p12.toString(), Optional.empty(), Optional.empty(),
                true, Optional.empty()).validate()).hasMessageContaining("api-key-file");
    }

    @Test
    void orchestratorSignsAfterBuildingAndRebuildsWhenSigningChanges() throws Exception {
        AtomicInteger builds = new AtomicInteger();
        NativeBuildRunner runner = new NativeBuildRunner() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public NativeBuildResult build(NativeBuildRequest request, BuildContext context) throws Exception {
                builds.incrementAndGet();
                Path exe = request.outputDirectory().resolve(request.executableName());
                Files.writeString(exe, "unsigned");
                return NativeBuildResult.of(exe);
            }
        };
        ExecutableSigner fakeSigner = new ExecutableSigner() {
            @Override
            public String name() {
                return "fake-signature";
            }

            @Override
            public boolean appliesTo(Platform platform) {
                return platform.os() == Platform.Os.WINDOWS;
            }

            @Override
            public String fingerprint() {
                return "fake key";
            }

            @Override
            public void sign(Path executable, Platform platform, BuildLog log, Path workDirectory) throws Exception {
                Files.writeString(executable, "signed");
            }
        };
        NativeSources sources = NativeSources.read(TestSupport.nativeSources(tmp));
        List<MatrixTarget> targets = List.of(
                new MatrixTarget("windows-amd64", Platform.parse("windows-amd64"), "fake", RunnerOptions.empty(),
                        Duration.ofSeconds(10)),
                new MatrixTarget("linux-amd64", Platform.parse("linux-amd64"), "fake", RunnerOptions.empty(),
                        Duration.ofSeconds(10)));

        var signed = run(runner, sources, targets, List.of(fakeSigner));
        assertThat(tmp.resolve("out/windows-amd64/app-runner.exe")).hasContent("signed");
        assertThat(tmp.resolve("out/linux-amd64/app-runner")).hasContent("unsigned");
        assertThat(signed.targets().get(0).result().orElseThrow().metadata()).containsEntry("signed", "fake-signature");
        assertThat(builds).hasValue(2);

        // unchanged: reused; signing removed: the Windows target is rebuilt
        run(runner, sources, targets, List.of(fakeSigner));
        assertThat(builds).hasValue(2);
        var unsigned = run(runner, sources, targets, List.of());
        assertThat(builds).hasValue(3);
        assertThat(unsigned.targets()).extracting(TargetResult::status)
                .containsExactly(TargetResult.Status.SUCCESS, TargetResult.Status.REUSED);
    }

    private NativeMatrixOrchestrator.MatrixResult run(NativeBuildRunner runner, NativeSources sources,
            List<MatrixTarget> targets, List<ExecutableSigner> signers) throws Exception {
        return new NativeMatrixOrchestrator(new RunnerRegistry(List.of(runner)),
                new NativeMatrixOrchestrator.Settings(tmp.resolve("out"), 2, false, false, false, null, null, null, null,
                        true, signers))
                .run(sources, targets);
    }
}
