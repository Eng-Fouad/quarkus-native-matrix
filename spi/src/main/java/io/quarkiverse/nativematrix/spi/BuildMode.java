package io.quarkiverse.nativematrix.spi;

/**
 * How a target is built.
 */
public enum BuildMode {

    /**
     * Only {@code native-image} runs on the builder, from the native sources produced once by the Quarkus build. Fast,
     * and the builder only needs GraalVM. All targets share the bytecode, configuration and dependencies resolved on
     * the build machine.
     */
    SOURCES,

    /**
     * The whole project is built on the builder ({@code mvn package -Dquarkus.native.enabled=true}...). Slower, and
     * the builder needs a JDK/GraalVM, the build tool (or its wrapper) and access to the artifact repositories, but
     * dependencies selected per OS (Maven profiles activated by {@code <os>}, platform classifiers such as JavaFX or
     * Netty native transports) and host-dependent build steps are resolved on the target platform.
     */
    FULL
}
