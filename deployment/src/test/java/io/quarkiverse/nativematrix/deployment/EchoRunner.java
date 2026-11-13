package io.quarkiverse.nativematrix.deployment;

import java.nio.file.Files;
import java.nio.file.Path;

import io.quarkiverse.nativematrix.spi.BuildContext;
import io.quarkiverse.nativematrix.spi.NativeBuildRequest;
import io.quarkiverse.nativematrix.spi.NativeBuildResult;
import io.quarkiverse.nativematrix.spi.NativeBuildRunner;
import io.quarkiverse.nativematrix.spi.support.ArgFile;

/**
 * A custom runner, registered through {@code META-INF/services} in the test resources, exactly like a user would.
 * It writes the argument file it was given instead of an executable.
 */
public class EchoRunner implements NativeBuildRunner {

    @Override
    public String id() {
        return "echo";
    }

    @Override
    public void validate(NativeBuildRequest request) {
        request.options().require("greeting");
    }

    @Override
    public NativeBuildResult build(NativeBuildRequest request, BuildContext context) throws Exception {
        context.log().info(request.options().require("greeting") + " from " + request.platform());
        Path exe = request.outputDirectory().resolve(request.executableName());
        Files.writeString(exe, ArgFile.render(request.nativeImageArgs()));
        return NativeBuildResult.of(exe);
    }
}
