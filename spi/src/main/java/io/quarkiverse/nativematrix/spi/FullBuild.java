package io.quarkiverse.nativematrix.spi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import io.quarkiverse.nativematrix.spi.support.Archives;
import io.quarkiverse.nativematrix.spi.support.FileTrees;

/**
 * What a runner needs to build a target in {@link BuildMode#FULL} mode: the project to ship and the commands that build
 * it on the builder.
 * <p>
 * The contract is the same for every runner: copy the project (filtered by {@link #includes(Path)}) to a directory of
 * the builder, run {@link #script(Platform)} from that directory with the platform shell ({@code sh} on Linux and
 * macOS, {@code cmd.exe} on Windows), then fetch the {@value NativeBuildRequest#DEFAULT_OUTPUT_DIR} directory, which
 * then holds the executable and its companion shared libraries.
 *
 * @param projectDirectory the local root of the project (the directory holding the root {@code pom.xml} or
 *        {@code settings.gradle})
 * @param posixCommand the build command for Linux and macOS builders
 * @param windowsCommand the build command for Windows builders
 * @param executableDirectory where the build writes the executable, relative to the project root, with forward slashes
 *        (e.g. {@code app/target})
 * @param excludedDirectories names of directories never shipped ({@code target}, {@code .git}...)
 * @param additionalFiles files shipped with the project although they are not part of it, by path relative to the
 *        project root: e.g. SNAPSHOT dependencies only installed on the build machine
 */
public record FullBuild(Path projectDirectory, String posixCommand, String windowsCommand, String executableDirectory,
        Set<String> excludedDirectories, Map<String, Path> additionalFiles) {

    public FullBuild {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        Objects.requireNonNull(posixCommand, "posixCommand");
        Objects.requireNonNull(windowsCommand, "windowsCommand");
        executableDirectory = executableDirectory == null || executableDirectory.isBlank() ? "."
                : executableDirectory.replace('\\', '/');
        excludedDirectories = excludedDirectories == null ? Set.of() : Set.copyOf(excludedDirectories);
        additionalFiles = additionalFiles == null ? Map.of() : Map.copyOf(additionalFiles);
    }

    public FullBuild(Path projectDirectory, String posixCommand, String windowsCommand, String executableDirectory,
            Set<String> excludedDirectories) {
        this(projectDirectory, posixCommand, windowsCommand, executableDirectory, excludedDirectories, Map.of());
    }

    /**
     * @return the build command for the platform of the builder
     */
    public String command(Platform platform) {
        return platform.os() == Platform.Os.WINDOWS ? windowsCommand : posixCommand;
    }

    /**
     * @return the path of the executable produced by the build, relative to the project root
     */
    public String executablePath(Platform platform, String outputName) {
        return executableDirectory + "/" + platform.executableName(outputName);
    }

    /**
     * @return a shell script that builds the project and copies the executable and the shared libraries next to it
     *         into the {@code output} directory
     */
    public String script(Platform platform, String outputName) {
        String out = NativeBuildRequest.DEFAULT_OUTPUT_DIR;
        if (platform.os() == Platform.Os.WINDOWS) {
            String dir = executableDirectory.replace('/', '\\');
            String exe = executablePath(platform, outputName).replace('/', '\\');
            return command(platform)
                    + " && (if not exist " + out + " mkdir " + out + ")"
                    + " && copy /y \"" + exe + "\" " + out + "\\"
                    + " && (if exist \"" + dir + "\\*.dll\" copy /y \"" + dir + "\\*.dll\" " + out + "\\)";
        }
        String exe = executablePath(platform, outputName);
        return command(platform)
                + " && mkdir -p " + out
                + " && cp '" + exe + "' " + out + "/"
                + " && for f in '" + executableDirectory + "'/*.so '" + executableDirectory + "'/*.dylib;"
                + " do if [ -f \"$f\" ]; then cp \"$f\" " + out + "/; fi; done";
    }

    /**
     * @param relativePath a path relative to the project root
     * @return whether the path must be shipped to the builder
     */
    public boolean includes(Path relativePath) {
        for (Path segment : relativePath) {
            String name = segment.toString();
            if (excludedDirectories.contains(name) || WINDOWS_DEVICE.matcher(name).matches()) {
                return false;
            }
        }
        return true;
    }

    /** Reserved Windows device names: files with these names cannot be read on Windows. */
    private static final java.util.regex.Pattern WINDOWS_DEVICE = java.util.regex.Pattern
            .compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?");

    /**
     * Copies the project, without the excluded directories, into {@code destination}. For Linux and macOS builders,
     * shell scripts ({@code mvnw}, {@code gradlew}, {@code *.sh}) checked out with Windows line endings are converted,
     * otherwise {@code sh} fails on the carriage returns.
     */
    public Path stage(Path destination, Platform platform) throws IOException {
        FileTrees.copy(projectDirectory, destination, this::includes);
        for (Map.Entry<String, Path> extra : additionalFiles.entrySet()) {
            Path target = destination.resolve(extra.getKey());
            Files.createDirectories(target.getParent());
            Files.copy(extra.getValue(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        if (platform.os().isPosix()) {
            for (Map.Entry<String, byte[]> script : normalizedScripts().entrySet()) {
                Files.write(destination.resolve(script.getKey()), script.getValue());
            }
        }
        return destination;
    }

    /**
     * Zips the project, without the excluded directories, plus additional entries. Shell scripts are normalized for
     * Linux and macOS builders, see {@link #stage(Path, Platform)}.
     */
    public Path zip(Path zipFile, Map<String, byte[]> extraEntries, Platform platform) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        for (Map.Entry<String, Path> extra : additionalFiles.entrySet()) {
            entries.put(extra.getKey(), Files.readAllBytes(extra.getValue()));
        }
        if (platform.os().isPosix()) {
            entries.putAll(normalizedScripts());
        }
        entries.putAll(extraEntries);
        return Archives.zip(projectDirectory, zipFile, entries, this::includes);
    }

    /**
     * @return the shell scripts of the project that contain carriage returns, with LF line endings, by relative path
     */
    Map<String, byte[]> normalizedScripts() throws IOException {
        Map<String, byte[]> scripts = new HashMap<>();
        for (Path file : FileTrees.listFiles(projectDirectory, this::includes)) {
            String name = file.getFileName().toString();
            if (name.equals("mvnw") || name.equals("gradlew") || name.endsWith(".sh")) {
                String content = Files.readString(file, StandardCharsets.ISO_8859_1);
                if (content.indexOf('\r') >= 0) {
                    scripts.put(projectDirectory.relativize(file).toString().replace('\\', '/'),
                            content.replace("\r\n", "\n").getBytes(StandardCharsets.ISO_8859_1));
                }
            }
        }
        return scripts;
    }

    /**
     * @return a predicate usable with {@link FileTrees} and {@link Archives}
     */
    public Predicate<Path> filter() {
        return this::includes;
    }
}
