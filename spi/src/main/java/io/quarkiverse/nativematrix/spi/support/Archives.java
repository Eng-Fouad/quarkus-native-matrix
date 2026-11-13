package io.quarkiverse.nativematrix.spi.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zip helpers, to ship the native sources to remote builders in one transfer.
 */
public final class Archives {

    private Archives() {
    }

    /**
     * Zips the content of {@code directory} (not the directory itself) into {@code zipFile}. Entry names use forward
     * slashes.
     */
    public static Path zip(Path directory, Path zipFile) throws IOException {
        return zip(directory, zipFile, Map.of());
    }

    /**
     * Zips the content of {@code directory} plus additional in-memory entries (which win over files of the same name),
     * e.g. a target specific argument file.
     */
    public static Path zip(Path directory, Path zipFile, Map<String, byte[]> extraEntries) throws IOException {
        return zip(directory, zipFile, extraEntries, p -> true);
    }

    /**
     * Zips the files of {@code directory} accepted by {@code filter} (which receives paths relative to
     * {@code directory}; rejected directories are not walked) plus additional in-memory entries.
     */
    public static Path zip(Path directory, Path zipFile, Map<String, byte[]> extraEntries, Predicate<Path> filter)
            throws IOException {
        List<Path> files = FileTrees.listFiles(directory, filter);
        Files.createDirectories(zipFile.toAbsolutePath().getParent());
        try (OutputStream os = Files.newOutputStream(zipFile); ZipOutputStream zos = new ZipOutputStream(os)) {
            for (Path file : files) {
                String name = directory.relativize(file).toString().replace('\\', '/');
                if (extraEntries.containsKey(name)) {
                    continue;
                }
                ZipEntry entry = new ZipEntry(name);
                entry.setLastModifiedTime(Files.getLastModifiedTime(file));
                zos.putNextEntry(entry);
                Files.copy(file, zos);
                zos.closeEntry();
            }
            for (Map.Entry<String, byte[]> extra : extraEntries.entrySet()) {
                zos.putNextEntry(new ZipEntry(extra.getKey()));
                zos.write(extra.getValue());
                zos.closeEntry();
            }
        }
        return zipFile;
    }

    /**
     * Extracts {@code zipFile} into {@code directory}. Entries escaping the target directory are rejected.
     *
     * @return the extracted regular files
     */
    public static List<Path> unzip(Path zipFile, Path directory) throws IOException {
        try (InputStream in = Files.newInputStream(zipFile)) {
            return unzip(in, directory);
        }
    }

    /**
     * Extracts a zip stream into {@code directory}. Entries escaping the target directory are rejected.
     *
     * @return the extracted regular files
     */
    public static List<Path> unzip(InputStream zipStream, Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        var extracted = new java.util.ArrayList<Path>();
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("Zip entry outside of the target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                    extracted.add(target);
                }
            }
        }
        return extracted;
    }
}
