# Agent instructions

## Repository facts

- This is the existing PlugIntell RAG Pipeline Studio project. Preserve its current behavior; do not replace it with a greenfield scaffold.
- The canonical build root is the directory containing this file and the root `pom.xml`.
- Backend language/runtime: Java 21. Build: Maven multi-module reactor. HTTP framework: Micronaut.
- Frontend: static HTML/CSS/JavaScript in `pipeline-builder-ui/`, bundled and served by `pipeline-builder-api/`. There is no React/Vite/Node frontend build in the current application.
- Metadata persistence: SQLite by default. Pipeline plugin code runs in-process and must be treated as trusted.
- Read [README.md](README.md), [ARCHITECTURE.md](ARCHITECTURE.md), and the relevant guide under `docs/` before changing a subsystem. Verify documented claims against source and build scripts when they conflict.

## Change rules

1. Reuse the existing plugin SPI and Micronaut application services. Add adapters where needed; do not duplicate execution logic across REST, MCP, UI, or CLI.
2. Keep pipeline drafts editable and published versions immutable. Bind executions to a specific published version.
3. Keep configuration schemas in plugin manifests authoritative for validation and Studio forms. Never persist or return resolved secret values; use references such as `secret://env/NAME`.
4. Validate filesystem paths, plugin IDs, graph structure, and user input at the backend boundary. Do not trust browser validation alone.
5. Treat plugin JARs as trusted code. A class loader is not a sandbox; do not describe it as one.
6. Avoid adding a new framework, module, or dependency unless the existing architecture cannot meet the requirement.
7. Update user and developer documentation when behavior, setup, security boundaries, or commands change. Remove obsolete claims rather than appending contradictory guidance.
8. Do not claim a feature is complete until its code compiles, relevant tests pass, and actual scope/limitations are documented.

## Build and verification

Run commands from the repository root with JDK 21 and Maven 3.9 or newer:

```powershell
mvn -pl pipeline-builder-api -am test
mvn test
mvn package -DskipTests
node --check pipeline-builder-ui/studio.js
```

For Windows startup, `build.bat` packages the reactor and `run.bat` opens the local Studio. `run-backend.bat` starts only the packaged backend. Read the scripts before changing their environment handling.

Choose the narrowest relevant verification while iterating, then run broader verification for cross-module changes. Report commands and outcomes accurately. Do not mask test/build failures or state that unrun checks passed.

## Documentation conventions

- Use standard Markdown headings, tables, fenced code blocks with language tags, and relative links within the repository.
- Distinguish implemented behavior, target architecture, and future work.
- Keep setup instructions aligned with `pom.xml`, `.mvn/maven.config`, `build.bat`, `run.bat`, and application configuration.
- Never include real credentials, tokens, user data, or machine-specific secrets in committed examples.

