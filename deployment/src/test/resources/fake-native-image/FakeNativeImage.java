import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stands in for native-image in tests. Run as a single-file program: {@code java FakeNativeImage.java @argfile}.
 * <p>
 * It expands {@code @argfile}s with its own implementation of the JDK argument file rules (the ones native-image
 * follows), independent from the writer under test.
 */
public class FakeNativeImage {
    public static void main(String[] argv) throws Exception {
        List<String> args = new ArrayList<>();
        for (String a : argv) {
            if (a.startsWith("@")) {
                args.addAll(parseArgFile(Files.readString(Path.of(a.substring(1)), StandardCharsets.UTF_8)));
            } else {
                args.add(a);
            }
        }
        int o = args.indexOf("-o");
        int jar = args.lastIndexOf("-jar");
        if (o < 0 || jar < 0) {
            System.err.println("Missing -o or -jar in " + args);
            System.exit(2);
        }
        Path jarFile = Path.of(args.get(jar + 1));
        if (!Files.isRegularFile(jarFile)) {
            System.err.println("Runner jar not found: " + jarFile.toAbsolutePath());
            System.exit(3);
        }
        if (args.contains("--fail")) {
            System.err.println("Failing on purpose");
            System.exit(1);
        }
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path out = Path.of(args.get(o + 1) + (windows ? ".exe" : ""));
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.write(Path.of(args.get(o + 1) + ".args"), args);
        Files.writeString(out, "fake executable");
        System.out.println("Fake native-image wrote " + out);
    }

    /** JDK rules: whitespace separated; '#' starts a comment; quotes group, and inside quotes backslash escapes. */
    static List<String> parseArgFile(String content) {
        List<String> result = new ArrayList<>();
        StringBuilder current = null;
        char quote = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && i + 1 < content.length()) {
                    char n = content.charAt(++i);
                    current.append(switch (n) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case 'f' -> '\f';
                        default -> n;
                    });
                } else {
                    current.append(c);
                }
            } else if (Character.isWhitespace(c)) {
                if (current != null) {
                    result.add(current.toString());
                    current = null;
                }
            } else if (c == '#' && current == null) {
                while (i < content.length() && content.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '"' || c == '\'') {
                if (current == null) {
                    current = new StringBuilder();
                }
                quote = c;
            } else {
                if (current == null) {
                    current = new StringBuilder();
                }
                current.append(c);
            }
        }
        if (current != null) {
            result.add(current.toString());
        }
        return result;
    }
}
