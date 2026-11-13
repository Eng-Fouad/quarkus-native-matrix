package io.quarkiverse.nativematrix.deployment.orchestrator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.quarkiverse.nativematrix.spi.support.FileTrees;

/**
 * Makes the {@code native-image} arguments portable: files and directories of the build machine referenced with
 * absolute paths (agent configuration directories, trust stores, configuration files...) are copied into the native
 * sources and the arguments are rewritten to relative paths, so that every builder receives them with the sources.
 * <p>
 * Quarkus does the same for the trust store of container builds; this generalizes it to any argument.
 */
public final class PathRelocator {

    /** Directory of the native sources receiving the relocated files. */
    public static final String EXTRA_DIRECTORY = "native-matrix-extra";

    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:[\\\\/].*");

    /** Options whose value is always a path (list): a missing path there deserves a warning. */
    private static final List<String> PATH_OPTIONS = List.of("-H:ConfigurationFileDirectories=",
            "-H:ReflectionConfigurationFiles=", "-H:ResourceConfigurationFiles=", "-H:JNIConfigurationFiles=",
            "-H:SerializationConfigurationFiles=", "-H:DynamicProxyConfigurationFiles=", "-J-Djavax.net.ssl.trustStore=");

    /**
     * @param args the arguments with portable paths
     * @param relocated the local paths copied into the native sources
     * @param unresolved arguments referencing absolute paths that do not exist on the build machine
     */
    public record Result(List<String> args, List<String> relocated, List<String> unresolved) {
    }

    private PathRelocator() {
    }

    public static Result relocate(List<String> args, Path nativeSourcesDirectory) throws IOException {
        Path sources = nativeSourcesDirectory.toAbsolutePath().normalize();
        Path extra = sources.resolve(EXTRA_DIRECTORY);
        FileTrees.delete(extra);
        Map<Path, String> copies = new LinkedHashMap<>();
        List<String> result = new ArrayList<>(args.size());
        List<String> unresolved = new ArrayList<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            String prefix = eq >= 0 ? arg.substring(0, eq + 1) : "";
            String value = eq >= 0 ? arg.substring(eq + 1) : arg;
            String[] parts = value.split(",", -1);
            boolean changed = false;
            boolean missing = false;
            for (int i = 0; i < parts.length; i++) {
                Path path = absolutePath(parts[i]);
                if (path == null) {
                    continue;
                }
                if (path.startsWith(sources)) {
                    parts[i] = slashes(sources.relativize(path));
                    changed = true;
                } else if (Files.exists(path)) {
                    String target = copies.get(path);
                    if (target == null) {
                        String name = path.getFileName() == null ? "root" : path.getFileName().toString();
                        target = EXTRA_DIRECTORY + "/" + (copies.size() + 1) + "/" + name;
                        Path destination = sources.resolve(target);
                        Files.createDirectories(destination.getParent());
                        if (Files.isDirectory(path)) {
                            FileTrees.copy(path, destination);
                        } else {
                            Files.copy(path, destination);
                        }
                        copies.put(path, target);
                    }
                    parts[i] = target;
                    changed = true;
                } else {
                    missing = true;
                }
            }
            if (missing && PATH_OPTIONS.stream().anyMatch(arg::startsWith)) {
                unresolved.add(arg);
            }
            result.add(changed ? prefix + String.join(",", parts) : arg);
        }
        List<String> relocated = copies.keySet().stream().map(Path::toString).toList();
        return new Result(result, relocated, unresolved);
    }

    /**
     * @return the absolute path a value denotes on the build machine, or null if it is not an absolute path
     */
    static Path absolutePath(String value) {
        if (value.isEmpty() || !(value.startsWith("/") || WINDOWS_ABSOLUTE.matcher(value).matches())) {
            return null;
        }
        try {
            Path p = Path.of(value);
            return p.isAbsolute() ? p.normalize() : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static String slashes(Path p) {
        return p.toString().replace('\\', '/');
    }
}
