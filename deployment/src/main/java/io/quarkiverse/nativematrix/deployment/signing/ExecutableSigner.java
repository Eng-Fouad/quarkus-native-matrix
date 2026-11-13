package io.quarkiverse.nativematrix.deployment.signing;

import java.nio.file.Path;

import io.quarkiverse.nativematrix.spi.BuildLog;
import io.quarkiverse.nativematrix.spi.Platform;

/**
 * Signs executables on the build machine, after a target was built and downloaded, whatever the runner was.
 */
public interface ExecutableSigner {

    /**
     * @return a short name recorded in the manifest, e.g. {@code authenticode}
     */
    String name();

    boolean appliesTo(Platform platform);

    /**
     * @return a description of the signing configuration without any secret; a change invalidates reused executables
     */
    String fingerprint();

    /**
     * Signs {@code executable} in place.
     *
     * @param workDirectory a private scratch directory
     */
    void sign(Path executable, Platform platform, BuildLog log, Path workDirectory) throws Exception;
}
