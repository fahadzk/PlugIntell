# Build the Pipeline as a GraalVM Native Executable

This project can produce a standalone Windows executable that contains the pipeline core and the four example plugins. The native executable is an additional, fixed-plugin deployment mode; the regular `pipeline-core.jar` remains the dynamic JVM mode.

## Native Image and dynamic plugin JARs

GraalVM Native Image uses a closed-world build: the executable contains classes known while the image is being built. It cannot load arbitrary new plugin classes from JARs supplied after the executable has been built. The regular JVM command continues to accept independently supplied plugin JARs. The native executable instead embeds these four implementations in a fixed order:

```text
extract-text → static-splitter → test-embedder → test-writer
```

The native launcher instantiates those plugin classes directly, then uses the same `PipelineValidator` and `Pipeline` classes to check and execute their runtime manifests. This keeps the in-process execution and type handoff while making the native plugin set explicit at build time. Add or change embedded plugins in `pipeline-native/src/main/java/com/example/pipeline/nativeapp/NativePipelineMain.java`, then rebuild the executable.

## Requirements

- GraalVM JDK 21 with the `native-image` component installed.
- Apache Maven 3.9 or newer.
- On Windows, Microsoft Visual C++ build tools and a Windows SDK. Native Image invokes the local C/C++ toolchain to produce the executable.
- Build on the operating system and architecture where the executable will run. Native executables are platform-specific.

The project `pom.xml` targets Java 21. Maven uses the shared repository at `D:\Work\Main\.m2\repository`, configured in `.mvn/maven.config`. Maven downloads required dependencies into this shared directory when needed.

## Build on Windows PowerShell

Set `JAVA_HOME` to the GraalVM installation. For the installation used in this workspace:

```powershell
$env:JAVA_HOME = 'D:\Softwares\Installed\JAVA\GraalVM\sdkman\candidates\java\current'
$env:MAVEN_HOME = 'D:\Softwares\Installed\JAVA\apache-maven-3.10.0'
$env:PATH = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:PATH"

java -version
mvn -version
native-image.cmd --version
```

Build all modules, including the shaded JAR containing the native launcher and plugins:

```powershell
mvn clean package
```

The native-input JAR is `pipeline-native/target/pipeline-native.jar`. Convert it to a native executable:

```powershell
Push-Location pipeline-native/target
native-image.cmd --no-fallback -jar pipeline-native.jar pipeline-native
Pop-Location
```

`--no-fallback` makes the build fail if GraalVM cannot produce a native executable, instead of silently leaving a JVM-dependent fallback. The output is:

```text
pipeline-native/target/pipeline-native.exe
```

Native Image performs ahead-of-time compilation and linking, so the first build can take several minutes and use substantial memory. Keep the normal compiler and Visual Studio Build Tools available in the shell; Native Image detects the supported Visual Studio installation on Windows.

If Visual Studio is installed in a custom location and Native Image reports `Failed to find 'vcvarsall.bat'`, initialize the x64 compiler environment and run the image builder in that same command shell. For the Build Tools location used in this workspace:

```powershell
$vcvars = 'D:\Softwares\Installed\Microsoft\BuildTools\VC\Auxiliary\Build\vcvarsall.bat'
$nativeImage = Join-Path $env:JAVA_HOME 'bin\native-image.cmd'
$buildLine = "call `"$vcvars`" x64 && `"$nativeImage`" --no-fallback -jar pipeline-native.jar pipeline-native"

Push-Location pipeline-native/target
& $env:ComSpec /d /c $buildLine
$nativeExit = $LASTEXITCODE
Pop-Location
if ($nativeExit -ne 0) { throw "Native Image failed with exit code $nativeExit" }
```

The `vcvarsall.bat` path may differ on other Windows installations. Locate it under your Visual Studio or Build Tools installation and substitute that path.

## Run the executable

From the project root, run with the default `sample.txt` input:

```powershell
.\pipeline-native\target\pipeline-native.exe
```

Or provide a text file path:

```powershell
.\pipeline-native\target\pipeline-native.exe .\sample.txt
```

The executable reports the four embedded plugins, validates their links, and runs them in order. The extractor reads UTF-8 text, the splitter creates 80-character segments, the embedder generates deterministic fake vectors, and the writer reports the number stored in memory.

To run the same embedded composition on the JVM without building Native Image:

```powershell
java -jar pipeline-native/target/pipeline-native.jar .\sample.txt
```

## JVM mode and native mode

| Mode | Command | Plugin set | Runtime plugin JAR loading |
| --- | --- | --- | --- |
| Dynamic JVM | `java -jar pipeline-core/target/pipeline-core.jar plugin-a.jar plugin-b.jar` | Supplied on each invocation | Yes |
| Native executable | `pipeline-native.exe [input-file]` | Four implementations embedded when built | No |

The native launcher does not accept plugin JAR arguments. To distribute a different native composition, update the embedded plugin list and rebuild the executable. This is a Native Image closed-world limitation, not a pipeline validation option.

## Troubleshooting

- `native-image.cmd` is not recognized: confirm `JAVA_HOME` and that `$env:JAVA_HOME\bin` is on `PATH`; check `native-image.cmd --version`.
- Native Image reports that no Visual Studio compiler is available: install Visual Studio Build Tools with **Desktop development with C++**, the MSVC x64/x86 tools, and a Windows SDK. For nonstandard install locations, initialize the environment with `vcvarsall.bat` as shown above.
- The native build runs out of memory: close memory-heavy applications and retry; Native Image compilation needs more memory than running the resulting executable.
- A plugin class was added but does not appear in the native image: add its dependency to `pipeline-native/pom.xml` and instantiate it in `NativePipelineMain`, then rebuild the JAR and executable.
- A newly added plugin uses reflection, resources, JNI, serialization, or dynamic proxies: register the required Native Image reachability metadata and rebuild. The current direct-instantiation example avoids reflection for plugin discovery.

## GraalVM references

- [Native Image for GraalVM JDK 21](https://www.graalvm.org/jdk21/reference-manual/native-image/): closed-world analysis and native executable builds.
- [Windows installation requirements](https://www.graalvm.org/jdk21/docs/getting-started/windows/): Visual Studio, MSVC, and Windows SDK requirements.
- [Reflection support](https://www.graalvm.org/jdk21/reference-manual/native-image/dynamic-features/Reflection/): build-time metadata requirements for reflective access and class lookup.
