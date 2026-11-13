# Quarkus Native Matrix

Build Quarkus native executables for **several OS/architecture combinations from a single build**:
Linux, macOS and Windows on amd64 and arm64, on your machine, in containers, over SSH, on GitHub Actions,
or anywhere a custom runner can reach.

```
$ mvn package -Dquarkus.native.enabled=true -Dquarkus.native.sources-only=true

[io.quarkiverse.nativematrix] Native matrix summary:
  linux-amd64    SUCCESS   2m41s  linux-amd64/my-app-1.0.0-runner
  linux-arm64    SUCCESS   3m02s  linux-arm64/my-app-1.0.0-runner      host=graviton.lan
  macos-arm64    SUCCESS   1m58s  macos-arm64/my-app-1.0.0-runner      host=mac-mini.lan
  windows-amd64  SUCCESS   6m12s  windows-amd64/my-app-1.0.0-runner.exe run=https://github.com/acme/my-app/actions/runs/123
  Output: target/native-matrix
```

## How it works

GraalVM `native-image` cannot cross-compile: an executable must be built on its target platform. However,
everything *before* `native-image` is platform independent. So the extension:

1. lets Quarkus run once, with `quarkus.native.sources-only=true`, producing `target/native-sources`
   (runner jar, `lib/`, and the `native-image` arguments);
2. hands those sources to a **runner** per target, in parallel. The runner ships them to a machine of the right
   platform, runs `native-image` there and brings the executable back;
3. writes the executables to `target/native-matrix/<target>/` with a `native-matrix.json` manifest
   (status, duration, SHA-256, runner metadata).

Remote machines only need GraalVM/Mandrel. They need no Maven, no sources, and no agent.

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
quarkus.native-matrix.target.macos-arm64.options.host=mac-mini.lan
quarkus.native-matrix.target.macos-arm64.options.graalvm-home=/opt/graalvm-25

quarkus.native-matrix.target.windows-amd64.runner=github-actions
quarkus.native-matrix.runner.github-actions.repository=acme/my-app      # shared by all github-actions targets
quarkus.native-matrix.runner.github-actions.token=${GITHUB_TOKEN}
```

```shell
mvn package -Dquarkus.native.enabled=true -Dquarkus.native.sources-only=true
# only some targets:
mvn package -Dquarkus.native.enabled=true -Dquarkus.native.sources-only=true -Dquarkus.native-matrix.only=linux-arm64
```

> The arguments were generated for a given GraalVM version (`native-sources/graalvm.version`). Use the same major
> version on every builder.

## Configuration

| Property | Default | Description |
|---|---|---|
| `quarkus.native-matrix.enabled` | `true` | Enables the matrix (only active for native sources builds) |
| `quarkus.native-matrix.target."<name>".runner` | `local` for the build machine platform | `local`, `docker`, `ssh`, `github-actions` or a custom runner id |
| `quarkus.native-matrix.target."<name>".platform` | the target name | `<os>-<arch>`: `linux`, `macos`, `windows` × `amd64`, `arm64` |
| `quarkus.native-matrix.target."<name>".options.<key>` | | Runner options (see below) |
| `quarkus.native-matrix.target."<name>".enabled` | `true` | |
| `quarkus.native-matrix.target."<name>".timeout` | global timeout | |
| `quarkus.native-matrix.runner."<id>".<key>` | | Options shared by every target of a runner; target options win |
| `quarkus.native-matrix.only` | | Comma separated target names to build |
| `quarkus.native-matrix.parallelism` | number of targets | Concurrent builds |
| `quarkus.native-matrix.fail-fast` | `false` | Cancel the other targets when one fails |
| `quarkus.native-matrix.fail-on-error` | `true` | Fail the Quarkus build when a target fails |
| `quarkus.native-matrix.timeout` | `2H` | Per target |
| `quarkus.native-matrix.output-directory` | `native-matrix` | Relative to `target/` |
| `quarkus.native-matrix.keep-work-directories` | `false` | Keep `native-matrix/.work` for troubleshooting |
| `quarkus.native-matrix.quiet` | `false` | Log `native-image` output at DEBUG |

The extension adapts the generated arguments per target. For example, it drops the `heapdump` monitoring option
for Windows and ignores Linux-only static linking flags elsewhere. It warns about arguments that reference paths
of the build machine.

## Runners

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
| `user`, `memory`, `cpus` | | Container settings; try `user=root` if the image user cannot write `/project` |
| `entrypoint` | `native-image` | |
| `create-args` | | Extra comma separated `docker create` arguments |

### `ssh`

Builds on any machine reachable over SSH (Linux, macOS, Windows with OpenSSH) using SFTP and an exec channel.
Only GraalVM is required there, plus the MSVC build tools on Windows.

| Option | Default | Description |
|---|---|---|
| `host` | *required* | |
| `port`, `user` | `22`, local user | |
| `identity-file` | `~/.ssh/id_ed25519`, `id_ecdsa`, `id_rsa` | Comma separated |
| `identity-passphrase` | | Use an expression, e.g. `${SSH_KEY_PASSPHRASE}` |
| `known-hosts` | `~/.ssh/known_hosts` | |
| `strict-host-key-checking` | `true` | Unknown hosts are rejected |
| `graalvm-home` | | Otherwise `native-image` must be on the PATH of non-interactive sessions |
| `pre-command` | | Runs first in the same shell, e.g. `source ~/.sdkman/bin/sdkman-init.sh`, or on Windows `call "C:\...\vcvars64.bat"` |
| `work-directory` | `.quarkus-native-matrix` | Relative to the remote home, or absolute |
| `keep-remote-files` | `false` | |
| `connect-timeout` | `30s` | |

### `github-actions`

Builds on GitHub-hosted runners, so you get every platform without owning hardware. The native sources are
uploaded to a temporary **draft release**, which only collaborators can see and which is deleted afterwards.
The runner then dispatches a workflow and downloads the run artifact.

1. Commit the workflow as `.github/workflows/native-matrix.yml`. The first build writes it to
   `target/native-matrix/github/native-matrix.yml` and tells you to commit it. It is also in
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
    public void validate(NativeBuildRequest request) {
        request.options().require("instance-id"); // runs for every target before any build starts
    }

    @Override
    public NativeBuildResult build(NativeBuildRequest request, BuildContext context) throws Exception {
        Path zip = Archives.zip(request.nativeSourcesDirectory(), context.workDirectory().resolve("src.zip"),
                Map.of(ArgFile.DEFAULT_NAME, ArgFile.render(request.nativeImageArgs()).getBytes(UTF_8)));
        // ...upload, run `native-image @native-image.matrix.args` remotely, stream lines to context.log().output(...)
        // ...download output/ into request.outputDirectory()
        return NativeBuildResult.of(request.outputDirectory().resolve(request.executableName()));
    }
}
```

Register it in `META-INF/services/io.quarkiverse.nativematrix.spi.NativeBuildRunner`, in a jar on the build class path:
- **preferred:** the deployment module of a small Quarkus extension, which keeps it off the runtime class path;
- or a plain dependency of the application.

What the SPI gives you:
- `NativeBuildRequest`: platform, read-only native sources, adjusted `native-image` arguments (`-o` defaults to
  `output/<name>`), GraalVM version, builder image, merged options, output directory, timeout;
- `BuildContext`: a target-prefixed logger, a private work directory, and cancellation (the thread is interrupted
  on timeout or fail-fast, so clean up remote resources in `finally`);
- helpers: `ArgFile` (argument files survive every shell's quoting rules), `Archives`, `Processes` (streamed,
  interruptible processes), `LineOutputStream`, `FileTrees`.

## Building this project

```shell
mvn install
# Docker runner integration test against the local engine:
mvn -pl deployment surefire:test -Dtest=DockerRunnerIT -Dnative-matrix.it.docker=true
```

## Limitations of this first version

- macOS code signing/notarization and Windows Authenticode signing are not handled; sign the manifest's outputs afterwards.
- Executables built remotely only see build-time configuration; anything referencing absolute paths of the build
  machine (e.g. `-H:ConfigurationFileDirectories`) must exist on the builders too (a warning is logged).
- The `ssh` runner does not use an SSH agent yet: it reads key files.
- The matrix only runs for `quarkus.native.sources-only=true` builds; a plain native build logs a reminder instead.
