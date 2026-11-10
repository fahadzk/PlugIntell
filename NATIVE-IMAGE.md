# GraalVM Native Image

This project has two separate ways to create a fixed-plugin native application:

1. Studio **Create project** creates a Maven application from a published RAG project. Each nonempty ingestion/query lane must be linear. Choose the GraalVM native executable target and follow progress in its separate build tab; download the executable or project ZIP after success.
2. `tools/generate_native_launcher.py` generates a fixed-plugin launcher source/POM project. See [GENERATOR.md](GENERATOR.md) for its arguments and generated project layout.

Neither option creates a general runtime that can load arbitrary plugin JARs after native compilation. GraalVM Native Image uses a closed-world build: plugin implementations and dependencies must be reachable when the image is built. The normal JVM plugin loader remains the option for runtime-supplied plugin JARs.

## Requirements

- GraalVM JDK 21 with Native Image installed.
- Maven 3.9 or newer.
- On Windows, a supported Microsoft C/C++ build toolchain and Windows SDK.
- Build on the target operating system and architecture. Native executables are platform-specific.

Check tool availability before starting a build:

```powershell
java -version
mvn -version
native-image --version
```

Set `JAVA_HOME` to the GraalVM installation and ensure its `bin` directory is on `PATH`. On Windows, Native Image must be able to locate the MSVC compiler and Windows SDK. A first native build may take several minutes and use substantial memory.

## Studio project builder

Studio accepts a published project and an absolute empty/absent destination on the backend machine. Both workflows are packaged together and selected with `--mode ingestion --input <file>` or `--mode query --query <question>`. A generated project uses the `native` Maven profile and is built with `mvn -Pnative package`. The web service must itself be able to invoke Maven, and its `JAVA_HOME` must point to GraalVM for Native Image builds. On Windows, its process environment must also expose the supported native toolchain.

The generated native executable is named after the project and is placed under the generated project's `target/` directory (`.exe` on Windows). The web generator packages the artifact into the downloaded project ZIP. Native builds can fail when a selected plugin depends on reflection, dynamic proxies, JNI, or resources without the required reachability metadata.

## Python launcher generator

The independent source generator in `tools/generate_native_launcher.py` writes a Java source file and Maven POM. The generated project must have access to the API, core, and plugin artifacts declared in its POM, either from an installed local Maven repository or a configured remote repository. Follow the exact generated project instructions in [GENERATOR.md](GENERATOR.md), then build its native profile with:

```powershell
mvn -Pnative clean package
```

The Python utility is a development-time generator and is separate from the Studio control plane. A published Studio pipeline is configuration data; publishing does not itself create a native executable.

## Troubleshooting

- **Document/image parsing requires native sidecars:** some parser dependencies make GraalVM emit DLL/shared-library files (for example AWT/image codecs on Windows). Keep these beside the executable. The project ZIP preserves the complete generated target folder; the executable-only endpoint downloads just the main binary. A separate Java installation is not required for running that complete native distribution.
- **Angus activation fails with `NoClassDefFoundError` during analysis:** Tika's mailcap handlers require Jakarta Mail signatures. The document-splitter plugin bundles `jakarta.mail-api` for this analysis; rebuild that plugin and regenerate rather than disabling the activation feature. The Python native profile also declares this API.
- **`CleanerJava6Substitution.CLEANER_FIELD_OFFSET` cannot be found:** this indicates mixed Netty 4.1/4.2 libraries, rather than a broken C++ compiler. The reactor imports a shared Netty BOM for provider plugins and the web host; Python-generated web POMs use the same version. Rebuild the complete reactor with `mvn clean package -DskipTests`, restart Studio, and generate into a new empty folder. Updating only the generated POM cannot replace Netty classes already embedded in shaded plugin JARs. Studio checks copied libraries for conflicting Netty artifact versions before compilation. The generated `system`-scope warnings are unrelated to this substitution error.
- **`native-image` is unavailable:** verify `JAVA_HOME` points to GraalVM and that its `bin` directory is on `PATH`.
- **The Windows wrapper reports a missing path:** check `native-image --version` in the backend's environment. If the SDK's underlying Native Image executable works but `bin/native-image.cmd` fails, repair the SDK launcher/toolchain installation; this is separate from generated Java wiring.
- **No C/C++ compiler is found on Windows:** install Visual Studio Build Tools with Desktop development with C++ and a Windows SDK; verify the environment from the same shell used for Maven.
- **A plugin is missing from the executable:** include it as a compile-time dependency and direct-instantiation target in the generated launcher, then rebuild.
- **Reflection or resource lookup fails at runtime:** add the necessary GraalVM reachability metadata and rebuild.
- **Native build runs out of memory:** close memory-heavy processes or increase available build memory, then retry.


## Optional application capabilities

The event reporting API and shared runtime are framework-independent. CLI and Micronaut web/UI projects support native generation; Kafka native combinations remain rejected. Select Micronaut and the Local UI adapter, publish, and choose the native build target. After success, extract the ZIP and use `run.bat serve` / `sh run.sh serve`, or invoke `target/<Project>.exe serve` on Windows (`./target/<Project> serve` on Linux/macOS). The executable embeds its monitor assets, selected template and explicit assembly. External Local UI bundles, including Aurora Studio and Midnight Observatory, use the same native resource inclusion; choose and configure the view before publishing/generating. See [UI templates](docs/operations/ui-templates.md). It serves multiple runs with snapshots, answers/citation metadata, SSE replay and rolling logs. Java and `lib/` are only needed for rebuilding, not for running the native executable.

Python-generated web projects retain JVM `run.bat` / `run.sh` and supply `run-native.bat` / `run-native.sh` for the native binary. Build with `mvn -Pnative package`, then use `run-native.bat serve`. Both generators use Native Build Tools 0.10.6. Micronaut Graal metadata is generated while building the optional host library; the build-time processor is not shipped in the runtime. Monitor/template resources are included through the host library's native resource configuration. Browser launch uses optional OS commands without AWT and always retains the printed URL fallback. Selected provider libraries still have independent native reachability requirements; a generated profile alone is not a passed native build.

The plain-text extract/split/test-embedding/test-writing CLI chain was compiled and executed with Native Image on Windows. Maven-native packaging, provider/OCR native paths and external integrations have separate limits; see [verification results](docs/operations/events-verification.md).
