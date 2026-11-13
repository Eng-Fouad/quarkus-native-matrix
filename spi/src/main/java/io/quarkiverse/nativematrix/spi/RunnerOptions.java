package io.quarkiverse.nativematrix.spi;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Runner specific configuration of a target: a flat map of string keys to string values, with typed accessors.
 * <p>
 * Keys are matched exactly; by convention they use kebab-case, e.g. {@code identity-file}. Configuration expressions
 * such as {@code ${GITHUB_TOKEN}} have already been expanded by Quarkus.
 */
public final class RunnerOptions {

    private static final RunnerOptions EMPTY = new RunnerOptions(Map.of());

    private final Map<String, String> values;

    private RunnerOptions(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new TreeMap<>(values));
    }

    public static RunnerOptions of(Map<String, String> values) {
        return values.isEmpty() ? EMPTY : new RunnerOptions(values);
    }

    public static RunnerOptions empty() {
        return EMPTY;
    }

    /**
     * @return a copy where entries of {@code overrides} replace entries of this instance
     */
    public RunnerOptions with(Map<String, String> overrides) {
        var merged = new TreeMap<>(values);
        merged.putAll(overrides);
        return new RunnerOptions(merged);
    }

    public Map<String, String> asMap() {
        return values;
    }

    public Optional<String> get(String key) {
        String v = values.get(key);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v.trim());
    }

    public String get(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    /**
     * @throws IllegalArgumentException if the option is missing
     */
    public String require(String key) {
        return get(key).orElseThrow(() -> new IllegalArgumentException("Missing required option '" + key + "'"));
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return get(key).map(v -> switch (v.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> throw new IllegalArgumentException("Option '" + key + "' must be a boolean, got '" + v + "'");
        }).orElse(defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return get(key).map(v -> {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Option '" + key + "' must be an integer, got '" + v + "'");
            }
        }).orElse(defaultValue);
    }

    /**
     * Accepts ISO-8601 durations ({@code PT30S}), the Quarkus short forms ({@code 30s}, {@code 5m}, {@code 2h},
     * {@code 500ms}) and plain numbers, interpreted as seconds.
     */
    public Duration getDuration(String key, Duration defaultValue) {
        return get(key).map(v -> parseDuration(key, v)).orElse(defaultValue);
    }

    /**
     * @return the comma separated values of the option, trimmed, without blanks; empty if the option is absent
     */
    public List<String> getList(String key) {
        return get(key).map(v -> Arrays.stream(v.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList()).orElse(List.of());
    }

    static Duration parseDuration(String key, String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        try {
            if (v.startsWith("p")) {
                return Duration.parse(v.toUpperCase(Locale.ROOT));
            }
            if (v.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2).trim()));
            }
            char unit = v.charAt(v.length() - 1);
            if (Character.isDigit(unit)) {
                return Duration.ofSeconds(Long.parseLong(v));
            }
            long amount = Long.parseLong(v.substring(0, v.length() - 1).trim());
            return switch (unit) {
                case 's' -> Duration.ofSeconds(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                case 'd' -> Duration.ofDays(amount);
                default -> throw new IllegalArgumentException();
            };
        } catch (IllegalArgumentException | DateTimeParseException | StringIndexOutOfBoundsException e) {
            throw new IllegalArgumentException(
                    "Option '" + key + "' must be a duration such as 30s, 5m or PT1H, got '" + value + "'");
        }
    }

    @Override
    public String toString() {
        // never print values: they may contain secrets
        return "RunnerOptions" + values.keySet();
    }
}
