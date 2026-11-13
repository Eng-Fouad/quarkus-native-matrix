package io.quarkiverse.nativematrix.spi;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * An operating system / CPU architecture pair a native executable is built for, e.g. {@code linux-arm64}.
 * <p>
 * GraalVM {@code native-image} cannot cross-compile, so each platform must be built on a machine (or container) of that
 * exact platform. Runners advertise which platforms they can reach through
 * {@link NativeBuildRunner#supports(Platform)}.
 */
public record Platform(Os os, Arch arch) {

    public Platform {
        Objects.requireNonNull(os, "os");
        Objects.requireNonNull(arch, "arch");
    }

    public enum Os {
        LINUX("linux", ""),
        MACOS("macos", ""),
        WINDOWS("windows", ".exe");

        private final String id;
        private final String executableSuffix;

        Os(String id, String executableSuffix) {
            this.id = id;
            this.executableSuffix = executableSuffix;
        }

        public String id() {
            return id;
        }

        /**
         * @return the suffix native executables carry on this OS ({@code .exe} on Windows, empty elsewhere)
         */
        public String executableSuffix() {
            return executableSuffix;
        }

        public boolean isPosix() {
            return this != WINDOWS;
        }

        public static Os parse(String value) {
            String v = value.trim().toLowerCase(Locale.ROOT);
            return switch (v) {
                case "linux" -> LINUX;
                case "macos", "mac", "osx", "darwin" -> MACOS;
                case "windows", "win", "win32" -> WINDOWS;
                default -> throw new IllegalArgumentException(
                        "Unknown operating system '" + value + "', expected one of: linux, macos, windows");
            };
        }

        static Os current() {
            String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (name.contains("win")) {
                return WINDOWS;
            }
            if (name.contains("mac") || name.contains("darwin")) {
                return MACOS;
            }
            return LINUX;
        }
    }

    public enum Arch {
        AMD64("amd64"),
        ARM64("arm64");

        private final String id;

        Arch(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Arch parse(String value) {
            String v = value.trim().toLowerCase(Locale.ROOT);
            return switch (v) {
                case "amd64", "x86_64", "x86-64", "x64" -> AMD64;
                case "arm64", "aarch64" -> ARM64;
                default -> throw new IllegalArgumentException(
                        "Unknown architecture '" + value + "', expected one of: amd64, arm64");
            };
        }

        static Arch current() {
            return parse(System.getProperty("os.arch", "amd64"));
        }
    }

    /**
     * Parses {@code <os>-<arch>} (or {@code <os>/<arch>}, {@code <os>_<arch>}). Common aliases are accepted:
     * {@code darwin}, {@code osx}, {@code x86_64}, {@code aarch64}...
     *
     * @throws IllegalArgumentException if the value is not a platform
     */
    public static Platform parse(String value) {
        return tryParse(value).orElseThrow(() -> new IllegalArgumentException(
                "Invalid platform '" + value + "', expected <os>-<arch>, e.g. linux-amd64, macos-arm64, windows-amd64"));
    }

    /**
     * Same as {@link #parse(String)} but returns an empty optional instead of throwing.
     */
    public static Optional<Platform> tryParse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.trim();
        // split on the first separator that is followed by a known architecture ("x86_64" contains an underscore)
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '-' || c == '/' || c == '_') {
                try {
                    return Optional.of(new Platform(Os.parse(v.substring(0, i)), Arch.parse(v.substring(i + 1))));
                } catch (IllegalArgumentException ignored) {
                    // try the next separator
                }
            }
        }
        return Optional.empty();
    }

    /**
     * @return the platform of the JVM running the build
     */
    public static Platform host() {
        return new Platform(Os.current(), Arch.current());
    }

    /**
     * @return the canonical identifier, e.g. {@code linux-amd64}
     */
    public String id() {
        return os.id() + "-" + arch.id();
    }

    /**
     * @return the platform as understood by container runtimes, e.g. {@code linux/arm64}
     */
    public String containerPlatform() {
        return os.id() + "/" + arch.id();
    }

    /**
     * @return {@code baseName} with the OS specific executable suffix
     */
    public String executableName(String baseName) {
        return baseName + os.executableSuffix();
    }

    @Override
    public String toString() {
        return id();
    }
}
