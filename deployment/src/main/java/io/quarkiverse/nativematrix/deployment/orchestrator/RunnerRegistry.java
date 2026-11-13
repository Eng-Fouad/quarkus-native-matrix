package io.quarkiverse.nativematrix.deployment.orchestrator;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.TreeMap;

import io.quarkiverse.nativematrix.spi.NativeBuildRunner;

/**
 * The available {@link NativeBuildRunner}s, by id.
 */
public final class RunnerRegistry {

    private final Map<String, NativeBuildRunner> runners;

    public RunnerRegistry(Collection<NativeBuildRunner> runners) {
        Map<String, NativeBuildRunner> map = new TreeMap<>();
        for (NativeBuildRunner runner : runners) {
            NativeBuildRunner previous = map.putIfAbsent(runner.id(), runner);
            if (previous != null && previous.getClass() != runner.getClass()) {
                throw new IllegalStateException("Two native build runners use the id '" + runner.id() + "': "
                        + previous.getClass().getName() + " and " + runner.getClass().getName());
            }
        }
        this.runners = map;
    }

    /**
     * Discovers runners with {@link ServiceLoader}, from the thread context class loader (the Quarkus deployment class
     * loader during a build, which sees the extensions and the application dependencies) and from the class loader of
     * this extension.
     */
    public static RunnerRegistry discover() {
        Map<String, NativeBuildRunner> found = new LinkedHashMap<>();
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        for (ClassLoader cl : new ClassLoader[] { tccl, RunnerRegistry.class.getClassLoader() }) {
            if (cl == null) {
                continue;
            }
            try {
                for (NativeBuildRunner runner : ServiceLoader.load(NativeBuildRunner.class, cl)) {
                    found.putIfAbsent(runner.getClass().getName(), runner);
                }
            } catch (ServiceConfigurationError e) {
                throw new IllegalStateException("Unable to load a native build runner: " + e.getMessage(), e);
            }
        }
        return new RunnerRegistry(found.values());
    }

    public Optional<NativeBuildRunner> get(String id) {
        return Optional.ofNullable(runners.get(id));
    }

    public Collection<String> ids() {
        return runners.keySet();
    }
}
