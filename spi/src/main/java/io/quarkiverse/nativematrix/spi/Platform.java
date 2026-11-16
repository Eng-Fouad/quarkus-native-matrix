package io.quarkiverse.nativematrix.spi;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.smallrye.common.cpu.CPU;
import io.smallrye.common.os.OS;

/**
 * An operating system / CPU architecture pair a native executable is built for, e.g. {@code linux-arm64}.
 * <p>
 * The operating system and the CPU are the {@link OS} and {@link CPU} types of SmallRye Common, also used by Quarkus.
 * A platform is limited to the combinations GraalVM native executables are built for - {@link OS#LINUX},
 * {@link OS#MAC} and {@link OS#WINDOWS} on {@link CPU#x64} and {@link CPU#aarch64} - and carries the names used by
 * the configuration, the output directories and the manifest: {@code linux}, {@code macos}, {@code windows} and
 * {@code amd64}, {@code arm64}.
 * <p>
 * GraalVM {@code native-image} cannot cross-compile, so each platform must be built on a machine (or container) of that
 * exact platform. Runners advertise which platforms they can reach through
 * {@link NativeBuildRunner#supports(Platform)}.
 */
public record Platform(OS os, CPU cpu) {

    /** The operating systems a platform can have. */
    public static final Set<OS> SUPPORTED_OS = Set.of(OS.LINUX, OS.MAC, OS.WINDOWS);
    /** The CPUs a platform can have. */
    public static final Set<CPU> SUPPORTED_CPUS = Set.of(CPU.x64, CPU.aarch64);

    public Platform {
        Objects.requireNonNull(os, "os");
        Objects.requireNonNull(cpu, "cpu");
        if (!SUPPORTED_OS.contains(os) || !SUPPORTED_CPUS.contains(cpu)) {
            throw new IllegalArgumentException("Unsupported platform " + os + "/" + cpu
                    + ": native executables are built for linux, macos and windows on amd64 and arm64");
        }
    }

    /**
     * Parses {@code <os>-<arch>} (or {@code <os>/<arch>}, {@code <os>_<arch>}). Common aliases are accepted:
     * {@code mac}, {@code darwin}, {@code osx}, {@code x86_64}, {@code x64}, {@code aarch64}, {@code aarch_64}...
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
                Optional<OS> os = parseOs(v.substring(0, i));
                Optional<CPU> cpu = parseCpu(v.substring(i + 1));
                if (os.isPresent() && cpu.isPresent()) {
                    return Optional.of(new Platform(os.get(), cpu.get()));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * @return the supported operating system named {@code value}: {@code linux}, {@code macos} (or {@code mac},
     *         {@code osx}, {@code darwin}), {@code windows} (or {@code win}, {@code win32})
     */
    public static Optional<OS> parseOs(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "linux" -> Optional.of(OS.LINUX);
            case "macos", "mac", "osx", "darwin" -> Optional.of(OS.MAC);
            case "windows", "win", "win32" -> Optional.of(OS.WINDOWS);
            default -> Optional.empty();
        };
    }

    /**
     * @return the supported CPU named {@code value}, with the aliases of {@link CPU#forNameOpt(String)} ({@code amd64},
     *         {@code x86_64}, {@code arm64}...) plus the spellings of Maven classifiers ({@code aarch_64},
     *         {@code x86-64})
     */
    public static Optional<CPU> parseCpu(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT).replace("aarch_64", "aarch64").replace("x86-64", "x86_64");
        if (v.isEmpty()) {
            return Optional.empty();
        }
        return CPU.forNameOpt(v).filter(SUPPORTED_CPUS::contains);
    }

    /**
     * @return the platform of the JVM running the build ({@link OS#current()}, {@link CPU#host()})
     * @throws IllegalStateException if the build machine is not a supported platform
     */
    public static Platform host() {
        return tryHost().orElseThrow(() -> new IllegalStateException("The build machine (" + OS.current() + "/"
                + CPU.host() + ") is not a platform native executables are built for"));
    }

    /**
     * @return the platform of the JVM running the build, if it is a supported platform
     */
    public static Optional<Platform> tryHost() {
        OS os = OS.current();
        CPU cpu = CPU.host();
        return SUPPORTED_OS.contains(os) && SUPPORTED_CPUS.contains(cpu) ? Optional.of(new Platform(os, cpu))
                : Optional.empty();
    }

    /**
     * @return {@code linux}, {@code macos} or {@code windows}
     */
    public String osId() {
        return switch (os) {
            case LINUX -> "linux";
            case MAC -> "macos";
            case WINDOWS -> "windows";
            default -> throw new IllegalStateException(os.name());
        };
    }

    /**
     * @return {@code amd64} or {@code arm64}
     */
    public String cpuId() {
        return cpu == CPU.x64 ? "amd64" : "arm64";
    }

    /**
     * @return the canonical identifier, e.g. {@code linux-amd64}
     */
    public String id() {
        return osId() + "-" + cpuId();
    }

    /**
     * @return the platform as understood by container runtimes, e.g. {@code linux/arm64}
     */
    public String containerPlatform() {
        return osId() + "/" + cpuId();
    }

    public boolean isWindows() {
        return os == OS.WINDOWS;
    }

    /**
     * @return whether the platform is Linux or macOS
     */
    public boolean isPosix() {
        return os != OS.WINDOWS;
    }

    /**
     * @return the suffix native executables carry on this platform ({@code .exe} on Windows, empty elsewhere)
     */
    public String executableSuffix() {
        return isWindows() ? ".exe" : "";
    }

    /**
     * @return {@code baseName} with the OS specific executable suffix
     */
    public String executableName(String baseName) {
        return baseName + executableSuffix();
    }

    @Override
    public String toString() {
        return id();
    }
}
