package io.quarkiverse.nativematrix.spi.support;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Small file system helpers for runners.
 */
public final class FileTrees {

    private FileTrees() {
    }

    /**
     * Recursively copies the content of {@code source} into {@code target}, creating it if needed.
     */
    public static void copy(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Deletes a file or directory tree; does nothing if it does not exist.
     */
    public static void delete(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                deleteFile(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                deleteFile(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Best effort {@link #delete(Path)}, never throws.
     */
    public static void deleteQuietly(Path path) {
        try {
            delete(path);
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }

    private static void deleteFile(Path file) throws IOException {
        try {
            Files.delete(file);
        } catch (NoSuchFileException ignored) {
            // already gone
        } catch (IOException e) {
            // read-only files on Windows
            file.toFile().setWritable(true);
            Files.deleteIfExists(file);
        }
    }

    /**
     * @return the regular files directly or indirectly contained in {@code dir}, sorted
     */
    public static List<Path> listFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return new ArrayList<>(s.filter(Files::isRegularFile).sorted().toList());
        }
    }

    /**
     * Adds the executable bits on POSIX file systems (lost by zip archives and some transfers); no-op elsewhere.
     */
    public static void makeExecutable(Path file) throws IOException {
        try {
            Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(file));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException e) {
            file.toFile().setExecutable(true, false);
        }
    }
}
