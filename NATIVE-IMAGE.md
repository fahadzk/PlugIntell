# GraalVM Native Image

This project has two separate ways to create a fixed-plugin native application:

1. The legacy web generator at `/` creates a Maven project from an ordered compatible plugin chain. Choose **GraalVM native-image profile** to generate a POM with the `native` Maven profile; the current backend builds the bundle synchronously and downloads it as a ZIP.
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

## Legacy project generator

The legacy page accepts a compatible ordered plugin chain and an output format. A generated project uses the `native` Maven profile and is built with `mvn -Pnative package`. The web service must itself be able to invoke Maven, and its `JAVA_HOME` must point to GraalVM for Native Image builds. On Windows, its process environment must also expose the supported native toolchain.

The generated native executable is named after the project and is placed under the generated project's `target/` directory (`.exe` on Windows). The web generator packages the artifact into the downloaded project ZIP. Native builds can fail when a selected plugin depends on reflection, dynamic proxies, JNI, or resources without the required reachability metadata.

## Python launcher generator

The independent source generator in `tools/generate_native_launcher.py` writes a Java source file and Maven POM. The generated project must have access to the API, core, and plugin artifacts declared in its POM, either from an installed local Maven repository or a configured remote repository. Follow the exact generated project instructions in [GENERATOR.md](GENERATOR.md), then build its native profile with:

```powershell
mvn -Pnative clean package
```

The Python utility is a development-time generator and is separate from the Studio control plane. A published Studio pipeline is configuration data; publishing does not itself create a native executable.

## Troubleshooting

- **`native-image` is unavailable:** verify `JAVA_HOME` points to GraalVM and that its `bin` directory is on `PATH`.
- **No C/C++ compiler is found on Windows:** install Visual Studio Build Tools with Desktop development with C++ and a Windows SDK; verify the environment from the same shell used for Maven.
- **A plugin is missing from the executable:** include it as a compile-time dependency and direct-instantiation target in the generated launcher, then rebuild.
- **Reflection or resource lookup fails at runtime:** add the necessary GraalVM reachability metadata and rebuild.
- **Native build runs out of memory:** close memory-heavy processes or increase available build memory, then retry.

