package io.quarkiverse.nativematrix.deployment.runners;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

import io.quarkiverse.nativematrix.spi.BuildContext;

/**
 * File transfer and command execution on the remote machine of the {@link SshRunner}. Remote paths use forward slashes
 * and are relative to the home directory of the remote user (or absolute).
 */
interface SshTransport extends AutoCloseable {

    /**
     * Creates a remote directory and its parents.
     */
    void mkdirs(String remoteDir) throws IOException, InterruptedException;

    /**
     * Copies the content of {@code localDir} into {@code remoteDir}, which must not exist yet.
     */
    void upload(Path localDir, String remoteDir, BuildContext context) throws IOException, InterruptedException;

    /**
     * Writes a remote file.
     */
    void write(String remoteFile, byte[] content) throws IOException, InterruptedException;

    /**
     * Runs a command with the remote shell ({@code sh} or {@code cmd.exe}), from the home directory.
     *
     * @param scratchDir an existing remote directory the transport may use for temporary files
     * @return the exit code
     */
    int run(String command, String scratchDir, Consumer<String> output, BuildContext context, Duration timeout)
            throws IOException, InterruptedException;

    /**
     * Copies the content of {@code remoteDir} into {@code localDir}.
     */
    void download(String remoteDir, Path localDir) throws IOException, InterruptedException;

    /**
     * Deletes a remote directory tree; does nothing if it does not exist.
     */
    void delete(String remoteDir) throws IOException, InterruptedException;

    @Override
    void close() throws IOException;
}
