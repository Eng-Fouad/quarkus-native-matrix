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
        return 0;
    }
}
