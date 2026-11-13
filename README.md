# Quarkus Native Matrix

Build Quarkus native executables for **several OS/architecture combinations from a single build**:
Linux, macOS and Windows on amd64 and arm64, on your machine, in containers, over SSH, on GitHub Actions,
or anywhere a custom runner can reach.

📖 **Documentation:** [`docs/modules/ROOT/pages`](docs/modules/ROOT/pages/index.adoc), published on
docs.quarkiverse.io once the extension joins Quarkiverse, with the generated
[configuration reference](docs/modules/ROOT/pages/includes/quarkus-native-matrix.adoc).

```
$ mvn package -Dnative-matrix

[io.quarkiverse.nativematrix] Native matrix summary:
  linux-amd64    SUCCESS   2m41s  linux-amd64/my-app-1.0.0-runner        graalvm=25.0
  linux-arm64    REUSED       0ms  linux-arm64/my-app-1.0.0-runner        host=graviton.lan
  macos-arm64    SUCCESS   1m58s  macos-arm64/my-app-1.0.0-runner        host=mac-mini.lan
  windows-amd64  SUCCESS   6m12s  windows-amd64/my-app-1.0.0-runner.exe  run=https://github.com/acme/my-app/actions/runs/123
  Output: target/native-matrix
```

## How it works

GraalVM `native-image` cannot cross-compile: an executable must be built on its target platform. Each target of the
matrix is built in one of two **modes**:

- **`sources`** (default): Quarkus runs once on your machine with `quarkus.native.sources-only=true` and produces
  `target/native-sources` (runner jar, `lib/`, the `native-image` arguments). A **runner** ships them to a machine of
  the target platform and runs only `native-image` there. It's fast, the builder needs nothing but GraalVM, and every
  executable comes from the same bytecode.
- **`full`**: the runner ships the project and runs the whole build (`mvnw package -Dquarkus.native.enabled=true`...)
  on the builder. It's slower, and the builder needs the build tool or its wrapper and repository access. In exchange,
  dependencies selected per OS (JavaFX, LWJGL or Netty native classifiers, Maven profiles activated by `<os>`) and
  host-dependent build steps are resolved on the target platform.

The executables land in `target/native-matrix/<target>/`, next to a `native-matrix.json` manifest recording, for
each target, its status, duration, mode, SHA-256, builder GraalVM version and runner metadata.

Two safety checks protect `sources` builds:
- **Platform dependencies.** If `native-sources/lib` contains jars picked for the build machine (for example
  `javafx-graphics-25-win.jar`) that don't match a target, that target fails before anything is built, with a hint
  to use `mode=full`. Jars bundled on purpose for several platforms are left alone.
- **GraalVM version.** Each builder reports `native-image --version`. A `major.minor` different from the version the
  arguments were generated for (`native-sources/graalvm.version`) triggers a warning, or a failure if you configure
  it that way.

## Getting started

```xml
<dependency>
    <groupId>io.quarkiverse.nativematrix</groupId>
    <artifactId>quarkus-native-matrix</artifactId>
    <version>${quarkus-native-matrix.version}</version>
</dependency>
```

```properties
# Target names that are platforms need no 'platform' property.
# The target matching the build machine uses the local runner by default.
quarkus.native-matrix.target.linux-amd64.runner=docker

quarkus.native-matrix.target.linux-arm64.runner=docker
quarkus.native-matrix.target.linux-arm64.options.context=graviton        # a remote arm64 Docker engine

quarkus.native-matrix.target.macos-arm64.runner=ssh
quarkus.native-matrix.target.macos-arm64.options.host=mac-mini           # an alias of ~/.ssh/config works
quarkus.native-matrix.target.macos-arm64.options.graalvm-home=/opt/graalvm-25

quarkus.native-matrix.target.windows-amd64.runner=github-actions
quarkus.native-matrix.target.windows-amd64.mode=full                     # e.g. for JavaFX applications
quarkus.native-matrix.runner.github-actions.repository=acme/my-app      # shared by all github-actions targets
quarkus.native-matrix.runner.github-actions.token=${GITHUB_TOKEN}
```

```shell
mvn package -Dnative-matrix
# only some targets:
mvn package -Dnative-matrix -Dquarkus.native-matrix.only=linux-arm64
# Gradle:
./gradlew build -Dquarkus.native-matrix.build=true
```

`-Dnative-matrix` (or `-Dquarkus.native-matrix.build=true`) is a shortcut for
`-Dquarkus.native.enabled=true -Dquarkus.native.sources-only=true`. Explicit values of these properties win.

**Rerunning only what failed.** When a target's inputs (native sources or project, platform, runner, mode,
options) haven't changed and its executable is still in the output directory, the extension reuses it (status
`REUSED`). Run `mvn package -Dnative-matrix` again without `clean` and only failed or changed targets are rebuilt.
Set `quarkus.native-matrix.reuse-unchanged=false` to always rebuild.

**Releasing.** Set `quarkus.native-matrix.packaging.jreleaser=true` to get:
- per-target archives in `target/native-matrix/dist/`, named `<name>-<platform>` with JReleaser platform names
  (`.tar.gz` keeps the executable bit, `.zip` for Windows);
- a `target/native-matrix/jreleaser.yml` declaring them as a `BINARY` distribution, to merge into your JReleaser
  configuration.

**Signing.** Executables are signed on the build machine once they are built, whatever the runner, so signing keys
never leave it:
- **Windows (Authenticode)** with [Jsign](https://ebourg.github.io/jsign/), in-process, on any OS. It supports
  PKCS#12/JKS files, PKCS#11 tokens, and cloud key services (Azure Trusted Signing, Azure Key Vault, AWS KMS,
  Google Cloud KMS, HashiCorp Vault, DigiCert ONE, SSL.com eSigner...). Signatures are timestamped by default.
- **macOS** with [rcodesign](https://gregoryszorc.com/docs/apple-codesign/stable/), which runs on Linux and Windows
  too. Executables get the hardened runtime and can be notarized. Apple cannot staple a ticket to a bare executable,
  so Gatekeeper checks the notarization online.

```properties
quarkus.native-matrix.signing.windows.keystore=signing.p12
quarkus.native-matrix.signing.windows.storepass=${SIGNING_STOREPASS}
quarkus.native-matrix.signing.macos.p12-file=developer-id.p12
quarkus.native-matrix.signing.macos.p12-password=${APPLE_P12_PASSWORD}
quarkus.native-matrix.signing.macos.notarize=true
quarkus.native-matrix.signing.macos.api-key-file=app-store-connect-key.json
```

The key stores are opened before any target is built, so a wrong password fails fast. Linux executables are best
signed when publishing, e.g. with JReleaser's GPG or cosign signing.

## Configuration

| Property | Default | Description |
|---|---|---|
| `quarkus.native-matrix.enabled` | `true` | Enables the matrix (only active for native sources builds) |
| `quarkus.native-matrix.build` | `false` | One-command switch, also `-Dnative-matrix` |
| `quarkus.native-matrix.target."<name>".runner` | `local` for the build machine platform | `local`, `docker`, `ssh`, `github-actions` or a custom runner id |
| `quarkus.native-matrix.target."<name>".platform` | the target name | `<os>-<arch>`: `linux`, `macos`, `windows` × `amd64`, `arm64` |
| `quarkus.native-matrix.target."<name>".mode` | global mode | `sources` or `full` |
| `quarkus.native-matrix.target."<name>".options.<key>` | | Runner options (see below) |
| `quarkus.native-matrix.target."<name>".enabled` | `true` | |
| `quarkus.native-matrix.target."<name>".timeout` | global timeout | |
| `quarkus.native-matrix.runner."<id>".<key>` | | Options shared by every target of a runner; target options win |
| `quarkus.native-matrix.mode` | `sources` | Default mode of the targets |
| `quarkus.native-matrix.only` | | Comma separated target names to build |
| `quarkus.native-matrix.parallelism` | number of targets | Concurrent builds |
| `quarkus.native-matrix.fail-fast` | `false` | Cancel the other targets when one fails |
| `quarkus.native-matrix.fail-on-error` | `true` | Fail the Quarkus build when a target fails |
| `quarkus.native-matrix.timeout` | `2H` | Per target |
| `quarkus.native-matrix.platform-dependency-check` | `fail` | `fail`, `warn` or `off` |
| `quarkus.native-matrix.ignored-platform-dependencies` | | Jar name globs the check ignores, e.g. `*netty-transport-native-epoll*` |
| `quarkus.native-matrix.graalvm-version-check` | `warn` | `fail`, `warn` or `off` |
| `quarkus.native-matrix.reuse-unchanged` | `true` | Reuse executables whose inputs did not change |
| `quarkus.native-matrix.output-directory` | `native-matrix` | Relative to `target/` |
| `quarkus.native-matrix.keep-work-directories` | `false` | Keep `native-matrix/.work` for troubleshooting |
| `quarkus.native-matrix.quiet` | `false` | Log `native-image` output at DEBUG |
| `quarkus.native-matrix.full-build.project-directory` | detected | Root of the project shipped in `full` mode |
| `quarkus.native-matrix.full-build.build-tool` | detected | `maven` or `gradle` |
| `quarkus.native-matrix.full-build.args` | | Extra build arguments, e.g. `-Pprod` |
| `quarkus.native-matrix.full-build.skip-tests` | `true` | |
| `quarkus.native-matrix.full-build.posix-command` / `windows-command` | generated | Replace the whole build command |
| `quarkus.native-matrix.full-build.excluded-directories` | `target,build,.git,.idea,.vscode,.gradle,node_modules,.quarkus` | Never shipped |
| `quarkus.native-matrix.full-build.ship-snapshots` | `true` | Ship SNAPSHOT dependencies only installed on this machine |
| `quarkus.native-matrix.signing.windows.keystore` | | Enables Authenticode signing: key store file, or cloud store name |
| `quarkus.native-matrix.signing.windows.storetype` | `PKCS12` | Any Jsign store type: `JKS`, `PKCS11`, `AZURETRUSTEDSIGNING`, `AWS`, `GOOGLECLOUD`... |
| `quarkus.native-matrix.signing.windows.storepass` / `keypass` / `alias` / `certfile` | | Key store access |
| `quarkus.native-matrix.signing.windows.timestamp` / `tsaurl` | `true` / `http://timestamp.digicert.com` | |
| `quarkus.native-matrix.signing.windows.program-name` / `program-url` | | Shown by Windows |
| `quarkus.native-matrix.signing.macos.p12-file` | | Enables macOS signing: Developer ID Application certificate |
| `quarkus.native-matrix.signing.macos.p12-password` | | |
| `quarkus.native-matrix.signing.macos.command` | `rcodesign` | Comma separated |
| `quarkus.native-matrix.signing.macos.entitlements` | | Entitlements plist |
| `quarkus.native-matrix.signing.macos.notarize` / `api-key-file` | `false` | App Store Connect API key from `rcodesign encode-app-store-connect-api-key` |
| `quarkus.native-matrix.packaging.archives` | `false` | Per-target archives in `native-matrix/dist` |
| `quarkus.native-matrix.packaging.jreleaser` | `false` | Archives plus `native-matrix/jreleaser.yml` |
| `quarkus.native-matrix.packaging.name` | executable name without `-runner` | Distribution name |

In `full` mode the project root is the directory holding `settings.gradle(.kts)`, or the Maven root (`.mvn` or
`mvnw`, up to the repository root), or else the top of the chain of `pom.xml` files above the module. The generated
command uses the wrapper when there is one, builds only the module and what it needs (`-pl <module> -am`, or
`:<module>:build`), and disables the matrix on the builder.
- SNAPSHOT dependencies (and SNAPSHOT parent POMs) that only exist in your local Maven repository are shipped with
  the project, in `.native-matrix/repository`. Maven reads them through `-Dmaven.repo.local.tail` (Maven 3.9+),
  and Gradle through a generated init script.
- A parent POM that exists on disk outside the project directory fails the build before anything is shipped. The
  message names the `project-directory` to set.

The extension adapts the generated arguments to each target:
- **Paths:** files and folders of the build machine referenced with absolute paths (agent configuration
  directories, trust stores, configuration files...) are copied into the native sources, under
  `native-matrix-extra/`, and the arguments are rewritten to relative paths. Every builder gets them.
- **Platform-specific options:** it drops the `heapdump` monitoring option for Windows and ignores Linux-only
  static linking flags elsewhere.

## Runners

| Runner | Platforms | Modes | Builder needs |
|---|---|---|---|
| `local` | the build machine | `sources` | GraalVM |
| `docker` | Linux (amd64/arm64, local or remote engine) | `sources`, `full` | a builder image |
| `ssh` | Linux, macOS, Windows | `sources`, `full` | GraalVM (+ build tool for `full`) |
| `github-actions` | all six | `sources`, `full` | nothing: GitHub-hosted runners |

### `local`

Runs `native-image` on the build machine, and therefore only builds for its own platform.

| Option | Description |
|---|---|
| `graalvm-home` | Defaults to `GRAALVM_HOME`, `JAVA_HOME`, then the `PATH` |
| `command` | Comma separated command replacing `native-image` (a wrapper script...) |

### `docker`

Runs a builder container on a local **or remote** Docker/Podman engine. Files go through `docker cp`, not bind
mounts. Pointing `context`/`host` at an arm64 machine gives native-speed `linux-arm64` builds; a local amd64 engine
builds arm64 under QEMU, which works but is slow. Linux targets only.

| Option | Default | Description |
|---|---|---|
| `runtime` | `docker` | or `podman`, or a path |
| `context` / `host` | | `docker --context` / `-H` (`podman --connection` / `--url`), e.g. `ssh://me@arm-box` |
| `image` | `quarkus.native.builder-image`, else `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25` | |
| `pull` | `missing` | `always`, `never` |
| `platform` | target platform | e.g. `linux/arm64` |
| `user`, `memory`, `cpus` | `root` | Container settings (the container is thrown away after the build) |
| `entrypoint` / `shell` | `native-image` / `/bin/sh` | Entry point in `sources` / `full` mode |
| `cache-volumes` | `true` | `full` mode: keep `~/.m2` and `~/.gradle` in named volumes between builds |
| `check-version` | `true` | Run `native-image --version` in the image first |
| `create-args` | | Extra comma separated `docker create` arguments |

### `ssh`

Builds on any machine reachable over SSH: Linux, macOS, or Windows with OpenSSH. Only GraalVM is required there,
plus the MSVC build tools on Windows. Two clients are available:

- **`openssh`** uses your `ssh`/`scp`, so your **SSH agent**, `~/.ssh/config` aliases, `ProxyJump`, certificates
  and hardware keys all work;
- **`mina`** is an embedded client that needs no executable, but only reads key files;
- **`auto`** (the default) picks `openssh` when `ssh` and `scp` are on the PATH and no `identity-passphrase` is set.

| Option | Default | Description |
|---|---|---|
| `host` | *required* | Host name, or an alias of `~/.ssh/config` with `openssh` |
| `client` | `auto` | `auto`, `openssh`, `mina` |
| `port`, `user` | `22`; OpenSSH config, or the local user with `mina` | |
| `identity-file` | agent / OpenSSH config; `mina`: `~/.ssh/id_ed25519`, `id_ecdsa`, `id_rsa` | Comma separated |
| `identity-passphrase` | | `mina` only. Use an expression, e.g. `${SSH_KEY_PASSPHRASE}` |
| `known-hosts` | `~/.ssh/known_hosts` | |
| `strict-host-key-checking` | `true` | Unknown hosts are rejected |
| `graalvm-home` | | Otherwise `native-image` must be on the PATH of non-interactive sessions; becomes `JAVA_HOME` in `full` mode |
| `pre-command` | | Runs first in the same shell, e.g. `source ~/.sdkman/bin/sdkman-init.sh`, or on Windows `call "C:\...\vcvars64.bat"` |
| `work-directory` | `.quarkus-native-matrix` | Relative to the remote home, or absolute |
| `keep-remote-files`, `check-version`, `connect-timeout` | `false`, `true`, `30s` | |

### `github-actions`

Builds on GitHub-hosted runners, so you get every platform without owning hardware. The native sources (or the
project, in `full` mode) are uploaded to a temporary **draft release**, which only collaborators can see and which
is deleted afterwards. The runner then dispatches a workflow and downloads the run artifact.

1. Commit the workflow as `.github/workflows/native-matrix.yml`. The first build writes it to
   `target/native-matrix/github/native-matrix.yml` and tells you to commit it. The same happens when the committed
   workflow is outdated. It is also in
   [`deployment/src/main/resources/io/quarkiverse/nativematrix/github/native-matrix.yml`](deployment/src/main/resources/io/quarkiverse/nativematrix/github/native-matrix.yml).
2. Provide a token with `contents: write` and `actions: write` on the repository.

| Option | Default | Description |
|---|---|---|
| `repository` | *required* | `owner/name` |
| `token` | `GITHUB_TOKEN` / `GH_TOKEN` env | |
| `workflow` | `native-matrix.yml` | |
| `ref` | default branch | Branch holding the workflow |
| `runs-on` | `ubuntu-24.04`, `ubuntu-24.04-arm`, `macos-15`, `macos-15-intel`, `windows-2025`, `windows-11-arm` | Any label, including self-hosted runners |
| `java-version` | derived from `graalvm.version` | |
| `distribution` | `graalvm-community` | `graalvm`, `mandrel`, `liberica` |
| `api-url` | `https://api.github.com` | GHES: `https://HOST/api/v3` |
| `poll-interval`, `dispatch-timeout` | `15s`, `3m` | |
| `keep-release`, `artifact-retention-days` | `false`, `1` | |

If the build is cancelled or times out, the extension also cancels the workflow run.

## Writing a custom runner

Runners implement a small, Quarkus-independent SPI:

```xml
<dependency>
    <groupId>io.quarkiverse.nativematrix</groupId>
    <artifactId>quarkus-native-matrix-spi</artifactId>
</dependency>
```

```java
public class Ec2Runner implements NativeBuildRunner {

    @Override
    public String id() {
        return "aws-ec2";                       // quarkus.native-matrix.target.x.runner=aws-ec2
    }

    @Override
    public boolean supports(Platform platform) {
        return platform.os() == Platform.Os.LINUX;
    }

    @Override
    public boolean supports(BuildMode mode) {
        return true;                            // SOURCES only by default
    }

    @Override
    public void validate(NativeBuildRequest request) {
        request.options().require("instance-id"); // runs for every target before any build starts
    }

    @Override
    public NativeBuildResult build(NativeBuildRequest request, BuildContext context) throws Exception {
        Path zip;
        String command;
        if (request.mode() == BuildMode.FULL) {
            FullBuild full = request.requireFullBuild();
            zip = full.zip(context.workDirectory().resolve("project.zip"), Map.of(), request.platform());
            command = full.script(request.platform(), request.outputName());    // writes output/
        } else {
            zip = Archives.zip(request.nativeSourcesDirectory(), context.workDirectory().resolve("src.zip"),
                    Map.of(ArgFile.DEFAULT_NAME, ArgFile.render(request.nativeImageArgs()).getBytes(UTF_8)));
            command = "native-image @" + ArgFile.DEFAULT_NAME;                 // writes output/
        }
        // ...upload and unzip, run `native-image --version` and pass it to context.reportNativeImageVersion(...),
        // run the command, stream lines to context.log().output(...), download output/ into request.outputDirectory()
        return NativeBuildResult.of(request.outputDirectory().resolve(request.executableName()));
    }
}
```

Register it in `META-INF/services/io.quarkiverse.nativematrix.spi.NativeBuildRunner`, in a jar on the build class path:
- **preferred:** the deployment module of a small Quarkus extension, which keeps it off the runtime class path;
- or a plain dependency of the application.

What the SPI gives you:
- `NativeBuildRequest`: platform, mode, read-only native sources, adjusted `native-image` arguments (`-o` defaults
  to `output/<name>`), the `FullBuild` in `full` mode, GraalVM version, builder image, merged options, output
  directory, timeout;
- `BuildContext`: a target-prefixed logger, a private work directory, the GraalVM version check, and cancellation
  (the thread is interrupted on timeout or fail-fast, so clean up remote resources in `finally`);
- helpers: `ArgFile` (argument files survive every shell's quoting rules), `Archives`, `Processes` (streamed,
  interruptible processes), `LineOutputStream`, `FileTrees`.

## Building this project

```shell
mvn install
# Docker runner integration test against the local engine:
mvn -pl deployment surefire:test -Dtest=DockerRunnerIT -Dnative-matrix.it.docker=true
# Real native builds of integration-tests/sample-app (Docker, Mandrel builder image):
mvn -Pit -pl integration-tests/sample-app package -Dnative-matrix
#   + the build machine on Windows: -De2e.windows=true -Dquarkus.native-matrix.target.windows-amd64.options.graalvm-home=...
#   + full mode in Docker:          -De2e.full=true
```

## Notes

- The matrix runs inside the Quarkus build, after the native sources are produced, so a matrix build is a native
  sources build. That's what `-Dnative-matrix` does, and when every target uses `full` mode it only adds a few
  seconds of jar packaging.
- An absolute path in an argument that doesn't exist on the build machine can't be shipped. It's kept as is, with a
  warning, and must exist on the builders.
- `full` mode ships the project directory and the local SNAPSHOTs it can identify. Gradle included builds outside the
  project, and repositories that need credentials, must be reachable from the builders.
- If you replace the full-build commands, add `-Dmaven.repo.local.tail=.native-matrix/repository` (or the init
  script for Gradle) yourself when SNAPSHOTs are shipped.
