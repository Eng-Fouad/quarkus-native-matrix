package io.quarkiverse.nativematrix.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.nativematrix.spi.NativeBuildRunner;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkus.test.ProdBuildResults;
import io.quarkus.test.ProdModeTestResults;
import io.quarkus.test.QuarkusProdModeTest;

/**
 * A real Quarkus production build with {@code quarkus.native.sources-only=true}: the extension must pick up the native
 * sources and build two targets, one with the local runner (backed by a fake native-image) and one with a custom
 * runner discovered through {@link java.util.ServiceLoader}.
 */
class NativeMatrixBuildTest {

    static final String HOST = Platform.host().id();

    @RegisterExtension
    static final QuarkusProdModeTest config = new QuarkusProdModeTest()
            // a custom runner shipped as part of the application, registered with META-INF/services
            .withApplicationRoot(jar -> jar.addClass(EchoRunner.class)
                    .addAsResource(new StringAsset(EchoRunner.class.getName() + "\n"),
                            "META-INF/services/" + NativeBuildRunner.class.getName()))
            .setApplicationName("matrix-app")
            .setApplicationVersion("1.0")
            // the one-command switch: enables quarkus.native.enabled and quarkus.native.sources-only
            .overrideConfigKey("quarkus.native-matrix.build", "true")
            .overrideConfigKey("quarkus.native-matrix.packaging.jreleaser", "true")
            .overrideConfigKey("quarkus.native-matrix.packaging.name", "matrix")
            // the target named after the build machine platform defaults to the local runner
            .overrideConfigKey("quarkus.native-matrix.target." + HOST + ".options.command",
                    String.join(",", TestSupport.fakeNativeImageCommand()))
            .overrideConfigKey("quarkus.native-matrix.target.raspberry.platform", "linux-arm64")
            .overrideConfigKey("quarkus.native-matrix.target.raspberry.runner", "echo")
            .overrideConfigKey("quarkus.native-matrix.runner.echo.greeting", "hello")
            .overrideConfigKey("quarkus.native-matrix.target.disabled.platform", "windows-arm64")
            .overrideConfigKey("quarkus.native-matrix.target.disabled.runner", "echo")
            .overrideConfigKey("quarkus.native-matrix.target.disabled.enabled", "false");

    @ProdBuildResults
    ProdModeTestResults results;

    @Test
    void buildsEveryEnabledTarget() throws Exception {
        Path matrix = results.getBuildDir().resolve("native-matrix");
        String exe = Platform.host().executableName("matrix-app-runner");

        assertThat(matrix.resolve(HOST).resolve(exe)).hasContent("fake executable");
        assertThat(matrix.resolve("raspberry/matrix-app-runner")).content()
                .contains("-o\noutput/matrix-app-runner\n", "-jar\nmatrix-app-runner.jar");
        assertThat(matrix.resolve("disabled")).doesNotExist();
        assertThat(matrix.resolve(".work")).isEmptyDirectory();

        String manifest = Files.readString(matrix.resolve("native-matrix.json"));
        assertThat(manifest).contains("\"name\" : \"raspberry\"", "\"runner\" : \"echo\"", "\"status\" : \"success\"",
                "\"name\" : \"" + HOST + "\"", "\"runner\" : \"local\"");
        // the local builder reported its GraalVM version (the fake prints Mandrel 25.0.1)
        assertThat(manifest).contains("\"graalvm\" : \"25.0\"", "\"fingerprint\"");
        // packaging for distribution
        assertThat(matrix.resolve("dist/matrix-linux-aarch_64.tar.gz")).exists();
        assertThat(matrix.resolve("jreleaser.yml")).content().contains("matrix:", "type: BINARY",
                "native-matrix/dist/matrix-linux-aarch_64.tar.gz", "platform: linux-aarch_64");
        // the native sources produced by Quarkus are left untouched
        assertThat(results.getBuildDir().resolve("native-sources/native-image.args")).exists();
        assertThat(results.getBuildDir().resolve("native-sources/output")).doesNotExist();
    }
}
