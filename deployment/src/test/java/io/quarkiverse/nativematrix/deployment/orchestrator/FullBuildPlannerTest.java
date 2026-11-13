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
    void shipsSnapshotsInstalledOnlyOnThisMachine() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("m2/repository"));
        Path libDir = Files.createDirectories(repo.resolve("com/acme/lib/1.0-SNAPSHOT"));
        Path libJar = Files.writeString(libDir.resolve("lib-1.0-SNAPSHOT.jar"), "jar");
        Files.writeString(libDir.resolve("lib-1.0-SNAPSHOT.pom"), """
                <project><parent><groupId>com.acme</groupId><artifactId>acme-parent</artifactId>
                <version>2.0-SNAPSHOT</version></parent><artifactId>lib</artifactId></project>""");
        Files.writeString(libDir.resolve("lib-1.0-SNAPSHOT-sources.txt"), "not shipped");
        Path parentDir = Files.createDirectories(repo.resolve("com/acme/acme-parent/2.0-SNAPSHOT"));
        Files.writeString(parentDir.resolve("acme-parent-2.0-SNAPSHOT.pom"), "<project/>");
        Path released = Files.createDirectories(repo.resolve("org/released/r/1.0"));
        Path releasedJar = Files.writeString(released.resolve("r-1.0.jar"), "jar");

        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        Files.createDirectories(project.resolve(".mvn"));
        Path moduleJar = Files.writeString(project.resolve("module.jar"), "reactor module");

        FullBuild plan = FullBuildPlanner.plan(project.resolve("target"), new FullBuildPlanner.Settings(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), true,
                FullBuildPlanner.DEFAULT_EXCLUDES, true, List.of(
                        new LocalSnapshots.Artifact("com.acme", "lib", "1.0-SNAPSHOT", libJar),
                        new LocalSnapshots.Artifact("org.released", "r", "1.0", releasedJar),
                        new LocalSnapshots.Artifact("com.acme", "module", "1.0-SNAPSHOT", moduleJar)),
                Optional.empty()));

        assertThat(plan.additionalFiles()).containsOnlyKeys(
                ".native-matrix/repository/com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar",
                ".native-matrix/repository/com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.pom",
                ".native-matrix/repository/com/acme/acme-parent/2.0-SNAPSHOT/acme-parent-2.0-SNAPSHOT.pom");
        assertThat(plan.posixCommand()).contains("-Dmaven.repo.local.tail=.native-matrix/repository");
        Path staged = plan.stage(tmp.resolve("staged"), Platform.parse("linux-amd64"));
        assertThat(staged.resolve(".native-matrix/repository/com/acme/lib/1.0-SNAPSHOT/lib-1.0-SNAPSHOT.jar"))
                .hasContent("jar");

        // nothing to ship: no tail repository
        FullBuild none = FullBuildPlanner.plan(project.resolve("target"), new FullBuildPlanner.Settings(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), true,
                FullBuildPlanner.DEFAULT_EXCLUDES, false, List.of(), Optional.of(repo)));
        assertThat(none.additionalFiles()).isEmpty();
        assertThat(none.posixCommand()).doesNotContain("repo.local.tail");
    }

    @Test
    void gradleBuildsGetAnInitScriptForTheShippedRepository() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("m2/repository/com/acme/lib/1.0-SNAPSHOT"));
        Path jar = Files.writeString(repo.resolve("lib-1.0-SNAPSHOT.jar"), "jar");
        Path root = Files.createDirectories(tmp.resolve("g"));
        Files.writeString(root.resolve("settings.gradle"), "");
        FullBuild plan = FullBuildPlanner.plan(root.resolve("build"), new FullBuildPlanner.Settings(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), true,
                FullBuildPlanner.DEFAULT_EXCLUDES, true,
                List.of(new LocalSnapshots.Artifact("com.acme", "lib", "1.0-SNAPSHOT", jar)), Optional.empty()));
        assertThat(plan.posixCommand()).contains("--init-script .native-matrix/repository.init.gradle");
        assertThat(plan.additionalFiles().get(".native-matrix/repository.init.gradle")).content()
                .contains("maven { url = uri(\"${rootDir}/.native-matrix/repository\") }");
    }

    @Test
    void rejectsParentPomsOutsideTheProject() throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("company-parent"));
        Files.writeString(outside.resolve("pom.xml"), "<project><artifactId>company-parent</artifactId></project>");
        Path project = Files.createDirectories(tmp.resolve("apps/app"));
        Files.createDirectories(project.resolve(".mvn"));
        Files.writeString(project.resolve("pom.xml"), """
                <project><parent><groupId>com.acme</groupId><artifactId>company-parent</artifactId><version>1</version>
                <relativePath>../../company-parent/pom.xml</relativePath></parent><artifactId>app</artifactId></project>""");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> FullBuildPlanner.plan(project.resolve("target"),
                defaults())).hasMessageContaining("The parent POM").hasMessageContaining("is outside the project directory")
                .hasMessageContaining("project-directory to " + tmp.toAbsolutePath().normalize());

        // an empty relativePath means "from the repositories": nothing to check
        Files.writeString(project.resolve("pom.xml"), """
                <project><parent><groupId>com.acme</groupId><artifactId>company-parent</artifactId><version>1</version>
                <relativePath/></parent><artifactId>app</artifactId></project>""");
        assertThat(FullBuildPlanner.plan(project.resolve("target"), defaults()).projectDirectory())
                .isEqualTo(project.toAbsolutePath().normalize());
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
