package io.quarkiverse.nativematrix.deployment.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.nativematrix.spi.FullBuild;
import io.quarkiverse.nativematrix.spi.Platform;
import io.quarkiverse.nativematrix.spi.support.Archives;
import io.quarkiverse.nativematrix.spi.support.FileTrees;

class FullBuildPlannerTest {

    @TempDir
    Path tmp;

    private static FullBuildPlanner.Settings defaults() {
        return new FullBuildPlanner.Settings(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of("-Pprod"), true, FullBuildPlanner.DEFAULT_EXCLUDES);
    }

    @Test
    void plansAMavenMultiModuleBuildWithTheWrapper() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh\r\necho mvn\r\n");
        Files.writeString(root.resolve("mvnw.cmd"), "@echo mvn");
        Files.createDirectories(root.resolve("apps"));
        Files.writeString(root.resolve("apps/pom.xml"), "<project/>");
        Path module = Files.createDirectories(root.resolve("apps/cli"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Files.createDirectories(module.resolve("src/main/java"));
        Files.writeString(module.resolve("src/main/java/App.java"), "class App {}");
        Files.createDirectories(module.resolve("target/classes"));
        Files.writeString(module.resolve("target/classes/App.class"), "binary");
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/HEAD"), "ref");

        FullBuild plan = FullBuildPlanner.plan(module.resolve("target"), defaults());

        assertThat(plan.projectDirectory()).isEqualTo(root.toAbsolutePath().normalize());
        assertThat(plan.executableDirectory()).isEqualTo("apps/cli/target");
        assertThat(plan.posixCommand()).isEqualTo("sh ./mvnw -B package -DskipTests -pl apps/cli -am"
                + " -Dquarkus.native.enabled=true -Dquarkus.native.sources-only=false"
                + " -Dquarkus.native.container-build=false -Dquarkus.native-matrix.enabled=false -Pprod");
        assertThat(plan.windowsCommand()).startsWith("call mvnw.cmd -B package");

        assertThat(plan.script(Platform.parse("linux-arm64"), "cli-1.0-runner"))
                .contains("&& cp 'apps/cli/target/cli-1.0-runner' output/")
                .contains("'apps/cli/target'/*.so");
        assertThat(plan.script(Platform.parse("windows-amd64"), "cli-1.0-runner"))
                .contains("copy /y \"apps\\cli\\target\\cli-1.0-runner.exe\" output\\")
                .contains("apps\\cli\\target\\*.dll");

        // build outputs and VCS data are not shipped; scripts get LF line endings for Linux/macOS builders
        Path staged = plan.stage(tmp.resolve("staged"), Platform.parse("linux-amd64"));
        assertThat(staged.resolve("apps/cli/src/main/java/App.java")).exists();
        assertThat(staged.resolve("apps/cli/target")).doesNotExist();
        assertThat(staged.resolve(".git")).doesNotExist();
        assertThat(staged.resolve("mvnw")).hasContent("#!/bin/sh\necho mvn\n");
        Path windowsStaged = plan.stage(tmp.resolve("staged-win"), Platform.parse("windows-amd64"));
        assertThat(Files.readString(windowsStaged.resolve("mvnw"))).contains("\r\n");

        Path zip = plan.zip(tmp.resolve("p.zip"), Map.of(), Platform.parse("macos-arm64"));
        List<Path> files = Archives.unzip(zip, tmp.resolve("unzipped"));
        assertThat(files).extracting(p -> tmp.resolve("unzipped").relativize(p).toString().replace('\\', '/'))
                .containsExactlyInAnyOrder("pom.xml", "mvnw", "mvnw.cmd", "apps/pom.xml", "apps/cli/pom.xml",
                        "apps/cli/src/main/java/App.java");
        assertThat(tmp.resolve("unzipped/mvnw")).hasContent("#!/bin/sh\necho mvn\n");
        FileTrees.delete(tmp.resolve("unzipped"));
    }

    @Test
    void findsTheMavenRootThroughDirectoriesWithoutPom() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("repo"));
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve(".mvn/wrapper"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh");
        Path module = Files.createDirectories(root.resolve("integration-tests/sample-app")); // no pom in between
        Files.writeString(module.resolve("pom.xml"), "<project/>");

        FullBuild plan = FullBuildPlanner.plan(module.resolve("target"), defaults());
        assertThat(plan.projectDirectory()).isEqualTo(root.toAbsolutePath().normalize());
        assertThat(plan.posixCommand()).startsWith("sh ./mvnw -B package -DskipTests -pl integration-tests/sample-app -am");
    }

    @Test
    void plansAGradleModuleBuild() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("g"));
        Files.writeString(root.resolve("settings.gradle.kts"), "include(\"app\")");
        Files.writeString(root.resolve("gradlew"), "#!/bin/sh");
        Path module = Files.createDirectories(root.resolve("app"));
        Files.writeString(module.resolve("build.gradle.kts"), "");

        FullBuild plan = FullBuildPlanner.plan(module.resolve("build"), defaults());
        assertThat(plan.projectDirectory()).isEqualTo(root.toAbsolutePath().normalize());
        assertThat(plan.posixCommand()).startsWith("sh ./gradlew :app:build -x test -Dquarkus.native.enabled=true");
        assertThat(plan.windowsCommand()).startsWith("call gradle :app:build");
        assertThat(plan.executableDirectory()).isEqualTo("app/build");
    }

    @Test
    void usesExplicitSettings() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("single"));
        FullBuild plan = FullBuildPlanner.plan(project.resolve("target"), new FullBuildPlanner.Settings(
                Optional.of(project), Optional.of(FullBuildPlanner.BuildTool.MAVEN), Optional.empty(), Optional.empty(),
                List.of(), false, FullBuildPlanner.DEFAULT_EXCLUDES));
        assertThat(plan.posixCommand()).startsWith("mvn -B package -Dquarkus.native.enabled=true");
        assertThat(plan.executableDirectory()).isEqualTo("target");

        FullBuild custom = FullBuildPlanner.plan(project.resolve("target"), new FullBuildPlanner.Settings(
                Optional.of(project), Optional.empty(), Optional.of("make native"), Optional.of("nmake native"),
                List.of(), true, FullBuildPlanner.DEFAULT_EXCLUDES));
        assertThat(custom.command(Platform.parse("linux-amd64"))).isEqualTo("make native");
        assertThat(custom.command(Platform.parse("windows-amd64"))).isEqualTo("nmake native");
    }
}
