import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

/**
 * Stands in for external tools (rcodesign...) in tests: {@code java RecordArgs.java <log> <args...>} appends the
 * arguments to the log file, one invocation per line, plus the content of any {@code --p12-password-file}.
 */
public class RecordArgs {
    public static void main(String[] argv) throws Exception {
        Path log = Path.of(argv[0]);
        List<String> args = Arrays.asList(argv).subList(1, argv.length);
        StringBuilder line = new StringBuilder(String.join(" ", args));
        int pw = args.indexOf("--p12-password-file");
        if (pw >= 0) {
            line.append(" [password=").append(Files.readString(Path.of(args.get(pw + 1)))).append("]");
        }
        Files.writeString(log, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("recorded " + args.get(0));
    }
}
