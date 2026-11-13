package io.quarkiverse.nativematrix.sample;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Prints where it runs, so that a test can check each executable of the matrix was built for its platform and really
 * is a native executable.
 */
@QuarkusMain
public class HelloMatrix implements QuarkusApplication {

    @Override
    public int run(String... args) {
        String imageCode = System.getProperty("org.graalvm.nativeimage.imagecode", "jvm");
        System.out.println("Hello from " + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + " (" + imageCode + ")");
        // present when built with -Pe2e-snapshot: a SNAPSHOT dependency only installed on the build machine
        try (var in = HelloMatrix.class.getClassLoader().getResourceAsStream("native-matrix-greeting.txt")) {
            if (in != null) {
                System.out.println(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim());
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return 0;
    }
}
