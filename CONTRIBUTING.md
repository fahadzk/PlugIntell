# Contributing

Thanks for improving PlugIntell. This repository is a Java 21 Maven multi-module application with a Micronaut control plane and a static browser UI. Read [AGENTS.md](AGENTS.md) and [ARCHITECTURE.md](ARCHITECTURE.md) before changing shared contracts or execution behavior.

## Development setup

Install JDK 21 and Maven 3.9 or newer. From the repository root:

```powershell
mvn test
mvn package -DskipTests
node --check pipeline-builder-ui/studio.js
```

For a local Windows app, run `build.bat`, then `run.bat`. Set `JAVA_HOME`, `MAVEN_HOME`, or `MAVEN_REPO_LOCAL` if the defaults on your machine are not suitable. Provider integrations such as OCR, Ollama, and Neo4j are not needed by deterministic unit tests; a live integration test should state its external prerequisites.

## Change workflow

1. Keep changes focused and compatible with existing public plugin contracts unless a contract change is necessary.
2. Add or update tests for backend behavior, persistence changes, validation, security boundaries, and plugin lifecycle behavior.
3. For browser changes, run `node --check` and manually verify the relevant screen and API flow when practical.
4. Update API/schema documentation and user/developer guides when behavior changes.
5. Review generated files and ensure no secrets, database files, build outputs, or local configuration are included.

There is no repository-specific branching, commit-message, or pull-request template policy at this time. Follow the conventions of the hosting repository and keep pull requests small enough to review.

## Plugin contributions

Add a plugin as a Maven module under `plugins/`, register it in the root `pom.xml`, include its manifest at `src/main/resources/META-INF/PluginManifest.yaml`, and test its declared input/output and configuration contract. See the [plugin developer guide](docs/plugins/developer-guide.md).

Plugin code executes inside the application process. Contributions must not imply that plugin class loading isolates untrusted code. Never include provider secrets in manifests, source, fixtures, logs, or documentation.

