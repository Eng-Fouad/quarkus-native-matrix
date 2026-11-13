package io.quarkiverse.nativematrix.deployment.orchestrator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.support.FileTrees;

/**
 * Detects platform specific jars in the native sources that would make an executable built for another platform
 * broken.
 * <p>
 * Build tools pick some dependencies for the machine running the build: Maven profiles activated by {@code <os>},
 * Gradle/OS-detector based classifiers... The jar names give them away: JavaFX ({@code javafx-graphics-21-win.jar}),
 * LWJGL ({@code lwjgl-3.3.3-natives-macos-arm64.jar}), Netty native transports
 * ({@code netty-transport-native-epoll-4.1.100.Final-linux-x86_64.jar})...
 * <p>
 * A group of jars (same name, different platform classifiers) is a problem for a target when every variant matches the
 * build machine - the signature of a dependency selected for the build machine - and none matches the target. A group
 * that already bundles variants for several platforms was assembled on purpose and is left alone.
 */
public final class PlatformDependencies {

    private static final Pattern CLASSIFIED = Pattern.compile(
            "^(?<base>.+?)[-.](?:natives-)?(?<os>windows|win32|win|macos|mac|osx|linux)"
                    + "(?:[-_](?<arch>x86[-_]64|amd64|x64|aarch[-_]?64|arm64))?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * A jar built for a given OS, and possibly a given architecture.
     */
    record PlatformJar(String fileName, String group, Platform.Os os, Optional<Platform.Arch> arch) {

        boolean matches(Platform platform) {
            return os == platform.os() && arch.map(a -> a == platform.arch()).orElse(true);
        }
    }

    private PlatformDependencies() {
    }

    static Optional<PlatformJar> parse(String fileName) {
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return Optional.empty();
        }
        Matcher m = CLASSIFIED.matcher(fileName.substring(0, fileName.length() - 4));
        if (!m.matches()) {
            return Optional.empty();
        }
        Platform.Os os = Platform.Os.parse(m.group("os"));
        Optional<Platform.Arch> arch = Optional.ofNullable(m.group("arch"))
                .map(a -> Platform.Arch.parse(a.replace("aarch_64", "aarch64").replace("x86-64", "x86_64")));
        return Optional.of(new PlatformJar(fileName, m.group("base"), os, arch));
    }

    /**
     * @param libDirectory the {@code lib} directory of the native sources
     * @param host the platform of the machine that produced the native sources
     * @param target the platform of the executable
     * @param ignored file name globs ({@code *} wildcard) of jars to ignore
     * @return the names of the jars that make the native sources unsuitable for {@code target}
     */
    public static List<String> mismatches(Path libDirectory, Platform host, Platform target, List<String> ignored)
            throws IOException {
        List<Pattern> ignoredPatterns = ignored.stream().map(PlatformDependencies::glob).toList();
        Map<String, List<PlatformJar>> groups = new TreeMap<>();
        for (Path jar : FileTrees.listFiles(libDirectory)) {
            String name = jar.getFileName().toString();
            if (ignoredPatterns.stream().anyMatch(p -> p.matcher(name).matches())) {
                continue;
            }
            parse(name).ifPresent(j -> groups.computeIfAbsent(j.group(), k -> new ArrayList<>()).add(j));
        }
        List<String> mismatches = new ArrayList<>();
        for (List<PlatformJar> variants : groups.values()) {
            boolean selectedForHost = variants.stream().allMatch(j -> j.matches(host));
            boolean coversTarget = variants.stream().anyMatch(j -> j.matches(target));
            if (selectedForHost && !coversTarget) {
                variants.forEach(j -> mismatches.add(j.fileName()));
            }
        }
        return mismatches;
    }

    private static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }
}
