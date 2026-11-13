package io.quarkiverse.nativematrix.deployment.orchestrator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import io.quarkiverse.nativematrix.spi.FullBuild;
import io.quarkiverse.nativematrix.spi.support.FileTrees;

/**
 * Hashes the inputs of a target, to reuse the executable of a previous run when nothing changed.
 * <p>
 * Jars are hashed by entry names and contents, not by bytes: rebuilt jars with new timestamps but the same classes
 * produce the same fingerprint.
 */
final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * @return the hash of the files of {@code directory} accepted by {@code filter}
     */
    static String directory(Path directory, Predicate<Path> filter) throws IOException {
        MessageDigest digest = sha256();
        for (Path file : FileTrees.listFiles(directory, filter)) {
            update(digest, directory.relativize(file).toString().replace('\\', '/'));
            if (file.getFileName().toString().endsWith(".jar")) {
                hashJarContent(file, digest);
            } else {
                try (InputStream in = Files.newInputStream(file)) {
                    update(digest, in);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * @return the fingerprint of a target: its inputs hash plus everything in its configuration that can change the
     *         executable
     */
    static String target(String inputsHash, MatrixTarget target, FullBuild fullBuild) {
        MessageDigest digest = sha256();
        update(digest, inputsHash);
        update(digest, target.name());
        update(digest, target.platform().id());
        update(digest, target.runnerId());
        update(digest, target.mode().name());
        for (Map.Entry<String, String> option : new TreeMap<>(target.options().asMap()).entrySet()) {
            update(digest, option.getKey() + "=" + option.getValue());
        }
        if (fullBuild != null) {
            update(digest, fullBuild.posixCommand());
            update(digest, fullBuild.windowsCommand());
            update(digest, fullBuild.executableDirectory());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void hashJarContent(Path jar, MessageDigest digest) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    MessageDigest d = sha256();
                    update(d, zis);
                    entries.put(entry.getName(), d.digest());
                }
            }
        } catch (IOException e) {
            entries.clear();
        }
        if (entries.isEmpty()) {
            // not a zip (or an empty one): hash the bytes
            try (InputStream in = Files.newInputStream(jar)) {
                update(digest, in);
            }
            return;
        }
        List<String> names = new ArrayList<>(entries.keySet());
        for (String name : names) {
            update(digest, name);
            digest.update(entries.get(name));
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static void update(MessageDigest digest, InputStream in) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            digest.update(buffer, 0, n);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
