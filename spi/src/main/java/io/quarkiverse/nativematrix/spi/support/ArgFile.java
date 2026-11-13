package io.quarkiverse.nativematrix.spi.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes {@code @argfile}s understood by {@code native-image} (and by the {@code java} launcher, which uses the same
 * syntax).
 * <p>
 * Passing arguments through a file avoids every quoting problem of the shells sitting between the runner and
 * {@code native-image} ({@code sh}, {@code cmd.exe}, PowerShell, {@code ssh}...): arguments such as
 * {@code --exclude-config \Qio.netty\E ^/META-INF/native-image/.*} survive unchanged on every OS.
 */
public final class ArgFile {

    /** Conventional file name used by the built-in runners, next to the runner jar. */
    public static final String DEFAULT_NAME = "native-image.matrix.args";

    private ArgFile() {
    }

    /**
     * Writes one argument per line, quoting the ones that need it.
     */
    public static Path write(Path file, List<String> args) throws IOException {
        Files.writeString(file, render(args), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * @return the content of an argument file holding {@code args}
     */
    public static String render(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (String arg : args) {
            sb.append(quote(arg)).append('\n');
        }
        return sb.toString();
    }

    /**
     * Quotes an argument following the JDK argument file rules: unquoted tokens end at whitespace and are taken
     * literally (backslashes included); a token starting with {@code #} is a comment; inside double quotes,
     * {@code \} escapes the next character.
     */
    static String quote(String arg) {
        if (!arg.isEmpty() && !needsQuoting(arg)) {
            return arg;
        }
        StringBuilder sb = new StringBuilder(arg.length() + 2).append('"');
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\f' -> sb.append("\\f");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static boolean needsQuoting(String arg) {
        if (arg.charAt(0) == '#') {
            return true;
        }
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (Character.isWhitespace(c) || c == '"' || c == '\'') {
                return true;
            }
        }
        return false;
    }
}
