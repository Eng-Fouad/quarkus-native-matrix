package io.quarkiverse.nativematrix.deployment.orchestrator;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jboss.logging.Logger;

import io.quarkiverse.nativematrix.spi.NativeBuildResult;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.support.FileTrees;

/**
 * Packages the executables of a matrix build for distribution: one archive per target, and optionally a JReleaser
 * configuration declaring them.
 */
public final class Packager {

    private static final Logger LOG = Logger.getLogger("io.quarkiverse.nativematrix");
    public static final String DIST_DIRECTORY = "dist";
    public static final String JRELEASER_FILE = "jreleaser.yml";

    /**
     * An archive of one target.
     *
     * @param platform the target platform
     * @param jreleaserPlatform the platform name used by JReleaser, e.g. {@code osx-aarch_64}
     * @param archive the archive file
     * @param distributed whether the archive is declared in the JReleaser distribution (one per platform)
     */
    public record Package(Platform platform, String jreleaserPlatform, Path archive, boolean distributed) {
    }

    public record Packages(List<Package> packages) {
    }

    private Packager() {
    }

    /**
     * @return the JReleaser platform name of a platform: {@code linux-x86_64}, {@code linux-aarch_64},
     *         {@code osx-x86_64}, {@code osx-aarch_64}, {@code windows-x86_64}, {@code windows-aarch_64}
     */
    public static String jreleaserPlatform(Platform platform) {
        String os = switch (platform.os()) {
            case LINUX -> "linux";
            case MACOS -> "osx";
            case WINDOWS -> "windows";
        };
        String arch = platform.arch() == Platform.Arch.AMD64 ? "x86_64" : "aarch_64";
        return os + "-" + arch;
    }

    /**
     * Archives the executable (renamed {@code name}, plus the OS suffix) and its shared libraries of every successful
     * target into {@code <matrixDirectory>/dist/<name>-<jreleaser platform>.(tar.gz|zip)}.
     */
    public static Packages archive(NativeMatrixOrchestrator.MatrixResult result, Path matrixDirectory, String name)
            throws IOException {
        Path dist = matrixDirectory.resolve(DIST_DIRECTORY);
        FileTrees.delete(dist);
        Files.createDirectories(dist);
        List<Package> packages = new ArrayList<>();
        java.util.Set<Platform> packaged = new java.util.HashSet<>();
        for (TargetResult r : result.targets()) {
            if (!r.succeeded() || r.result().isEmpty()) {
                continue;
            }
            Platform platform = r.target().platform();
            String jrPlatform = jreleaserPlatform(platform);
            // several targets may build the same platform (e.g. sources and full mode): only the first one gets the
            // plain name and is part of the distribution
            boolean primary = packaged.add(platform);
            String root = name + "-" + jrPlatform
                    + (primary ? "" : "-" + NativeMatrixOrchestrator.sanitize(r.target().name()));
            if (!primary) {
                LOG.warnf("[%s] Another target already builds %s: archived as %s, not part of the JReleaser"
                        + " distribution", r.target().name(), platform, root);
            }
            NativeBuildResult res = r.result().get();
            List<Entry> entries = new ArrayList<>();
            entries.add(new Entry(platform.executableName(name), res.executable(), 0755));
            for (Path f : res.additionalFiles()) {
                String fileName = f.getFileName().toString().toLowerCase(Locale.ROOT);
                if (fileName.endsWith(".so") || fileName.endsWith(".dylib") || fileName.endsWith(".dll")) {
                    entries.add(new Entry(f.getFileName().toString(), f, 0644));
                }
            }
            Path archive;
            if (platform.os() == Platform.Os.WINDOWS) {
                archive = dist.resolve(root + ".zip");
                writeZip(archive, root, entries);
            } else {
                archive = dist.resolve(root + ".tar.gz");
                writeTarGz(archive, root, entries);
            }
            packages.add(new Package(platform, jrPlatform, archive, primary));
            LOG.infof("[%s] Packaged %s", r.target().name(), matrixDirectory.relativize(archive));
        }
        return new Packages(packages);
    }

    /**
     * Writes a JReleaser configuration declaring the archives as a {@code BINARY} distribution. Paths are relative to
     * the module directory (the parent of the build output directory), which is JReleaser's default base directory
     * when it runs from the module.
     */
    public static Path writeJReleaserConfig(Packages packages, Path matrixDirectory, String name) throws IOException {
        Path base = matrixDirectory.getParent().getParent();
        StringBuilder yml = new StringBuilder()
                .append("# Generated by quarkus-native-matrix. Merge into your jreleaser.yml, or use it directly:\n")
                .append("#   jreleaser full-release --config-file ").append(slashes(base.relativize(
                        matrixDirectory.resolve(JRELEASER_FILE))))
                .append("\n# (project, release and signing sections omitted)\n")
                .append("distributions:\n")
                .append("  ").append(name).append(":\n")
                .append("    type: BINARY\n")
                .append("    executable:\n")
                .append("      name: ").append(name).append('\n')
                .append("      windowsExtension: exe\n")
                .append("    artifacts:\n");
        for (Package p : packages.packages()) {
            if (!p.distributed()) {
                continue;
            }
            yml.append("      - path: ").append(slashes(base.relativize(p.archive()))).append('\n')
                    .append("        platform: ").append(p.jreleaserPlatform()).append('\n');
        }
        Path file = matrixDirectory.resolve(JRELEASER_FILE);
        Files.writeString(file, yml.toString(), StandardCharsets.UTF_8);
        return file;
    }

    record Entry(String name, Path file, int mode) {
    }

    private static void writeZip(Path archive, String root, List<Entry> entries) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Entry e : entries) {
                zos.putNextEntry(new ZipEntry(root + "/" + e.name()));
                Files.copy(e.file(), zos);
                zos.closeEntry();
            }
        }
    }

    /**
     * Minimal POSIX ustar writer, enough to keep the executable permission (zip archives created by the JDK cannot).
     */
    static void writeTarGz(Path archive, String root, List<Entry> entries) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(archive))) {
            long mtime = System.currentTimeMillis() / 1000;
            out.write(header(root + "/", 0755, 0, mtime, '5'));
            for (Entry e : entries) {
                long size = Files.size(e.file());
                out.write(header(root + "/" + e.name(), e.mode(), size, mtime, '0'));
                Files.copy(e.file(), out);
                int padding = (int) ((512 - size % 512) % 512);
                out.write(new byte[padding]);
            }
            out.write(new byte[1024]); // two empty blocks end the archive
        }
    }

    private static byte[] header(String name, int mode, long size, long mtime, char type) throws IOException {
        byte[] h = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 100) {
            int split = name.lastIndexOf('/', name.length() - 2);
            byte[] prefix = name.substring(0, split).getBytes(StandardCharsets.UTF_8);
            nameBytes = name.substring(split + 1).getBytes(StandardCharsets.UTF_8);
            if (prefix.length > 155 || nameBytes.length > 100) {
                throw new IOException("Path too long for a tar archive: " + name);
            }
            System.arraycopy(prefix, 0, h, 345, prefix.length);
        }
        System.arraycopy(nameBytes, 0, h, 0, nameBytes.length);
        octal(h, 100, 8, mode);
        octal(h, 108, 8, 0);
        octal(h, 116, 8, 0);
        octal(h, 124, 12, size);
        octal(h, 136, 12, mtime);
        h[156] = (byte) type;
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 6);
        h[263] = '0';
        h[264] = '0';
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        long checksum = 0;
        for (byte b : h) {
            checksum += b & 0xff;
        }
        String cs = String.format("%06o", checksum);
        System.arraycopy(cs.getBytes(StandardCharsets.US_ASCII), 0, h, 148, 6);
        h[154] = 0;
        h[155] = ' ';
        return h;
    }

    private static void octal(byte[] h, int offset, int length, long value) {
        String s = String.format("%0" + (length - 1) + "o", value);
        System.arraycopy(s.getBytes(StandardCharsets.US_ASCII), 0, h, offset, length - 1);
        h[offset + length - 1] = 0;
    }

    private static String slashes(Path p) {
        return p.toString().replace('\\', '/');
    }
}
