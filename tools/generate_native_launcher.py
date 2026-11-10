#!/usr/bin/env python3
"""Generate a Maven launcher project for fixed plugins or a two-stage RAG pipeline."""

from __future__ import annotations

import argparse
import re
import json
import os
import xml.etree.ElementTree as ET
from pathlib import Path

JAVA_IDENTIFIER = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*\Z")


def parse_plugins(value: str) -> list[str]:
    plugins = [part.strip() for part in value.split(",") if part.strip()]
    if not plugins:
        raise argparse.ArgumentTypeError("provide at least one comma-separated plugin class")
    for plugin in plugins:
        pieces = plugin.split(".")
        if len(pieces) < 2 or any(not JAVA_IDENTIFIER.fullmatch(piece) for piece in pieces):
            raise argparse.ArgumentTypeError(f"not a fully qualified Java class name: {plugin}")
    return plugins


def validate_identifier(value: str, label: str) -> str:
    if not JAVA_IDENTIFIER.fullmatch(value):
        raise argparse.ArgumentTypeError(f"{label} must be a Java identifier: {value}")
    return value


def render_single_source(package: str, main_class: str, plugins: list[str]) -> str:
    imports = "\n".join(f"import {plugin};" for plugin in plugins)
    instances = ",\n                ".join(f"new {plugin.rsplit('.', 1)[-1]}()" for plugin in plugins)
    return f'''package {package};

import com.core.docintel.pipeline.api.Plugin;
import com.core.docintel.pipeline.core.Pipeline;
import com.core.docintel.pipeline.core.PipelineValidator;
import java.nio.file.Path;
import java.util.List;
{imports}

/** Generated launcher. The plugin classes are embedded in the native image at build time. */
public final class {main_class} {{
    private {main_class}() {{}}

    public static void main(String[] args) {{
        if (args.length > 1) {{
            System.err.println("Usage: {main_class} [input-file]");
            System.exit(1);
        }}

        List<Plugin> plugins = List.of(
                {instances});
        try {{
            System.out.println("Loading embedded plugins...");
            for (int i = 0; i < plugins.size(); i++) {{
                var manifest = plugins.get(i).manifest();
                System.out.printf("%n[%d] %s%n    %s -> %s%n    AVAILABLE%n",
                        i + 1, manifest.id(), manifest.inputType(), manifest.outputType());
            }}

            System.out.println("\\nValidating embedded pipeline...\\n");
            new PipelineValidator().validate(plugins);
            for (int i = 1; i < plugins.size(); i++) {{
                var before = plugins.get(i - 1).manifest();
                var after = plugins.get(i).manifest();
                System.out.printf("%-20s -> %-20s OK%n", before.id(), after.id());
            }}

            Path input = args.length == 0 ? Path.of("sample.txt") : Path.of(args[0]);
            System.out.println("\\nNative pipeline is valid.\\n\\nExecuting...");
            new Pipeline(plugins).execute(input.toFile());
            System.out.println("\\nNative pipeline completed successfully.");
        }} catch (RuntimeException e) {{
            System.err.println("Pipeline failed: " + e.getMessage());
            System.exit(1);
        }}
    }}
}}
'''


def render_rag_source(package: str, main_class: str, ingest_plugins: list[str],
                      query_plugins: list[str]) -> str:
    plugins = list(dict.fromkeys(ingest_plugins + query_plugins))
    imports = "\n".join(f"import {plugin};" for plugin in plugins)
    ingest_instances = ",\n                ".join(f"new {name.rsplit('.', 1)[-1]}()" for name in ingest_plugins)
    query_instances = ",\n                ".join(f"new {name.rsplit('.', 1)[-1]}()" for name in query_plugins)
    return f'''package {package};

import com.core.docintel.pipeline.api.AnswerResult;
import com.core.docintel.pipeline.api.Plugin;
import com.core.docintel.pipeline.api.SearchRequest;
import com.core.docintel.pipeline.api.StoredResult;
import com.core.docintel.pipeline.core.Pipeline;
import com.core.docintel.pipeline.core.PipelineValidator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.function.Supplier;
{imports}

/** Generated two-flow RAG launcher. All plugin implementations are compiled into the app. */
public final class {main_class} {{
    private {main_class}() {{}}

    public static void main(String[] args) {{
        if (args.length == 0) {{ usage(); System.exit(2); }}
        try {{
            switch (args[0]) {{
                case "ingest" -> ingest(args);
                case "query" -> query(args);
                default -> {{ usage(); System.exit(2); }}
            }}
        }} catch (Exception e) {{
            System.err.println("Pipeline failed: " + e.getMessage());
            System.exit(1);
        }}
    }}

    private static void ingest(String[] args) {{
        if (args.length != 2) throw new IllegalArgumentException("Usage: ingest <pdf-file>");
        run(List.of({ingest_instances}), () -> {{
            Path pdf = Path.of(args[1]);
            if (!pdf.toFile().isFile() || !pdf.toFile().canRead())
                throw new IllegalArgumentException("PDF is not a readable file: " + pdf);
            return pdf.toFile();
        }});
    }}

    private static void query(String[] args) {{
        boolean citations = true;
        int topK = 5;
        for (int i = 1; i < args.length; i++) {{
            if (args[i].equals("--no-citations")) citations = false;
            else if (args[i].startsWith("--top-k=")) {{
                try {{ topK = Integer.parseInt(args[i].substring("--top-k=".length())); }}
                catch (NumberFormatException e) {{ throw new IllegalArgumentException("--top-k must be an integer."); }}
            }} else throw new IllegalArgumentException("Unknown query option: " + args[i]);
        }}
        if (topK < 1 || topK > 100) throw new IllegalArgumentException("--top-k must be between 1 and 100.");
        boolean includeCitations = citations;
        int requestedTopK = topK;
        run(List.of({query_instances}), () -> {{
            try (Scanner scanner = new Scanner(System.in)) {{
                System.out.print("Search query: ");
                if (!scanner.hasNextLine()) throw new IllegalArgumentException("No search query was entered.");
                String text = scanner.nextLine().trim();
                if (text.isEmpty()) throw new IllegalArgumentException("Search query must not be blank.");
                return new SearchRequest(text, includeCitations, requestedTopK);
            }}
        }});
    }}

    private static void run(List<Plugin> plugins, Supplier<Object> input) {{
        try (Pipeline pipeline = new Pipeline(plugins)) {{
            System.out.println("Validating complete flow and checking configured services...");
            new PipelineValidator().validate(plugins);
            for (int i = 0; i < plugins.size(); i++) {{
                var manifest = plugins.get(i).manifest();
                System.out.printf("[%d] %s: %s -> %s VALID%n", i + 1, manifest.id(), manifest.inputType(), manifest.outputType());
                if (i > 0) System.out.printf("%-24s -> %-24s OK%n", plugins.get(i - 1).manifest().id(), manifest.id());
            }}
            System.out.println("\\nFlow is valid.\\n\\nExecuting...");
            Object result = pipeline.execute(input.get());
            if (result instanceof StoredResult stored) {{
                System.out.println("Stored " + stored.storedChunks() + " embeddings.");
            }} else if (result instanceof AnswerResult answer) {{
                System.out.println("\\nAnswer:\\n" + answer.answer());
                if (answer.citationsEnabled()) {{
                    System.out.println("\\nSources:");
                    for (int i = 0; i < answer.citations().size(); i++) {{
                        var source = answer.citations().get(i);
                        System.out.printf("[%d] %s, page %d (score %.4f)%n", i + 1, source.sourceName(), source.pageNumber(), source.score());
                    }}
                }}
            }}
            System.out.println("\\nPipeline completed successfully.");
        }}
    }}

    private static void usage() {{
        System.err.println("Usage:\\n  {main_class} ingest <pdf-file>\\n  {main_class} query [--no-citations] [--top-k=N]");
    }}
}}
'''


def parse_dependency(value: str) -> tuple[str, str, str]:
    parts = value.split(":")
    if len(parts) != 3 or any(not re.fullmatch(r"[A-Za-z0-9_.-]+", part) for part in parts):
        raise argparse.ArgumentTypeError("dependency must use groupId:artifactId:version")
    return parts[0], parts[1], parts[2]


def render_pom(package: str, main_class: str, project_name: str,
               plugin_dependencies: list[tuple[str, str, str]], web: bool = False) -> str:
    group_id = package
    artifact_id = re.sub(r"[^A-Za-z0-9_.-]", "-", project_name).lower()
    # Shaded provider libraries must also be rebuilt with this version: Maven
    # cannot mediate dependencies already embedded inside plugin JARs.
    netty_version = ET.parse(Path(__file__).resolve().parents[1] / "pom.xml").findtext(
        "{*}properties/{*}netty.version")
    netty_management = (f'''  <dependencyManagement><dependencies><dependency>
    <groupId>io.netty</groupId><artifactId>netty-bom</artifactId><version>{netty_version}</version>
    <type>pom</type><scope>import</scope>
  </dependency></dependencies></dependencyManagement>''' if web else "")
    native_image_profile = f'''  <profiles>
    <profile>
      <id>native</id>
      <dependencies>
        <!-- Angus Activation's Native Image feature inspects Jakarta Mail handlers. -->
        <dependency>
          <groupId>jakarta.mail</groupId>
          <artifactId>jakarta.mail-api</artifactId>
          <version>2.1.3</version>
        </dependency>
      </dependencies>
      <build>
        <plugins>
          <plugin>
            <groupId>org.graalvm.buildtools</groupId>
            <artifactId>native-maven-plugin</artifactId>
            <version>0.10.6</version>
            <extensions>true</extensions>
            <configuration>
              <mainClass>{package}.{main_class}</mainClass>
              <imageName>{artifact_id}</imageName>
              <buildArgs>
                <buildArg>--no-fallback</buildArg>
                {"<buildArg>--initialize-at-run-time=io.netty,org.slf4j</buildArg>" if web else ""}
              </buildArgs>
            </configuration>
            <executions>
              <execution>
                <id>build-native-image</id>
                <phase>package</phase>
                <goals>
                  <goal>compile-no-fork</goal>
                </goals>
              </execution>
            </executions>
          </plugin>
        </plugins>
      </build>
    </profile>
  </profiles>'''
    plugin_dependency_xml = "\n".join(
        f"    <dependency><groupId>{group}</groupId><artifactId>{artifact}</artifactId><version>{version}</version></dependency>"
        for group, artifact, version in plugin_dependencies
    )
    dependency_comment = "" if plugin_dependencies else "    <!-- Add --dependency groupId:artifactId:version for each plugin class. -->\n"
    return f'''<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>{group_id}</groupId>
  <artifactId>{artifact_id}</artifactId>
  <version>1.0.0</version>
  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
{netty_management}
  <dependencies>
    <!-- Set these coordinates to the published or reactor artifacts that provide the shared pipeline API/core. -->
    <dependency><groupId>com.core.docintel</groupId><artifactId>pipeline-api</artifactId><version>1.0.0</version></dependency>
    <dependency><groupId>com.core.docintel</groupId><artifactId>pipeline-core</artifactId><version>1.0.0</version></dependency>
{dependency_comment}{plugin_dependency_xml}
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.0</version>
        <configuration><release>${{maven.compiler.release}}</release></configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId><version>3.2.4</version>
        <executions><execution><phase>package</phase><goals><goal>shade</goal></goals>
          <configuration>
            <filters>
              <filter><artifact>*:*</artifact><excludes>
                <exclude>META-INF/*.SF</exclude>
                <exclude>META-INF/*.DSA</exclude>
                <exclude>META-INF/*.RSA</exclude>
                <exclude>META-INF/*.EC</exclude>
              </excludes></filter>
            </filters>
            <transformers>
              <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                <mainClass>{package}.{main_class}</mainClass>
              </transformer>
              <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
            </transformers>
          </configuration>
        </execution></executions>
      </plugin>
    </plugins>
  </build>
{native_image_profile}
</project>
'''


def render_env_batch() -> str:
    return r'''@echo off
rem RAG pipeline settings for cmd.exe. Edit values, then run: call rag-env.bat

set "OLLAMA_BASE_URL=http://localhost:11434"
set "OLLAMA_EMBEDDING_MODEL=nomic-embed-text"
set "OLLAMA_CHAT_MODEL=llama3.1"
set "OLLAMA_TIMEOUT_SECONDS=60"

set "NEO4J_URI=bolt://localhost:7687"
set "NEO4J_USERNAME=neo4j"
set "NEO4J_PASSWORD=CHANGE_ME"
set "NEO4J_DATABASE=neo4j"
set "NEO4J_VECTOR_INDEX=document_embedding_idx"
set "NEO4J_VECTOR_DIMENSIONS=768"

rem Set this to your Tesseract installation folder if tesseract is not on PATH.
set "PDF_OCR_TESSERACT_PATH=C:\Program Files\Tesseract-OCR"
rem Optional overrides; leave unset to use Tesseract defaults.
rem set "PDF_OCR_TESSDATA_PATH=C:\Program Files\Tesseract-OCR\tessdata"
set "PDF_OCR_LANGUAGE=eng"

echo RAG environment variables loaded for this shell.
echo Confirm OLLAMA models, Neo4j credentials/index, and Tesseract path before running.
'''


def render_env_powershell() -> str:
    return '''# RAG pipeline settings for PowerShell. Edit values, then load with: . .\\rag-env.ps1
$env:OLLAMA_BASE_URL = "http://localhost:11434"
$env:OLLAMA_EMBEDDING_MODEL = "nomic-embed-text"
$env:OLLAMA_CHAT_MODEL = "llama3.1"
$env:OLLAMA_TIMEOUT_SECONDS = "60"

$env:NEO4J_URI = "bolt://localhost:7687"
$env:NEO4J_USERNAME = "neo4j"
$env:NEO4J_PASSWORD = "CHANGE_ME"
$env:NEO4J_DATABASE = "neo4j"
$env:NEO4J_VECTOR_INDEX = "document_embedding_idx"
$env:NEO4J_VECTOR_DIMENSIONS = "768"

# Set this to your Tesseract installation folder if tesseract is not on PATH.
$env:PDF_OCR_TESSERACT_PATH = "C:\\Program Files\\Tesseract-OCR"
# Optional: $env:PDF_OCR_TESSDATA_PATH = "C:\\Program Files\\Tesseract-OCR\\tessdata"
$env:PDF_OCR_LANGUAGE = "eng"

Write-Host "RAG environment variables loaded for this PowerShell session."
Write-Host "Confirm Ollama models, Neo4j credentials/index, and Tesseract path before running."
'''


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--plugins", type=parse_plugins,
                       help="comma-separated classes for one generic linear pipeline")
    group.add_argument("--ingest-plugins", type=parse_plugins,
                       help="comma-separated classes for the RAG PDF ingestion chain")
    parser.add_argument("--query-plugins", type=parse_plugins,
                        help="comma-separated classes for the RAG query/answer chain (requires --ingest-plugins)")
    parser.add_argument("--output", required=True, type=Path, help="directory where the project folder is created")
    parser.add_argument("--project-name", required=True, help="new project folder name")
    parser.add_argument("--main-class", required=True, help="generated Java class name")
    parser.add_argument("--package", required=True, help="generated Java package name")
    parser.add_argument("--dependency", action="append", type=parse_dependency, default=[],
                        help="plugin Maven dependency as groupId:artifactId:version; repeat once per plugin")
    parser.add_argument("--web", action="store_true", help="include the optional Micronaut HTTP host and serve command")
    parser.add_argument("--ui", action="store_true", help="attach the local UI adapter to both workflows (requires --web)")
    parser.add_argument("--kafka-bootstrap-servers", help="include the optional Kafka telemetry publisher")
    parser.add_argument("--kafka-topic", default="plugintell.events")
    parser.add_argument("--kafka-security-protocol", choices=("PLAINTEXT","SSL","SASL_SSL","SASL_PLAINTEXT"), default="PLAINTEXT")
    parser.add_argument("--kafka-username-ref", default="")
    parser.add_argument("--kafka-password-ref", default="")
    parser.add_argument("--template", choices=("standard","water","aurora","midnight"), default="standard")
    parser.add_argument("--capabilities", type=Path, help="versioned adapter/subscription/host/template JSON settings")
    parser.add_argument("--template-bundle", type=Path, help="versioned template ZIP for web applications")
    parser.add_argument("--trusted-template", action="store_true", help="acknowledge executable custom template as trusted code")
    parser.add_argument("--reporting-plugins", type=parse_plugins, default=[], help="trusted selected classes exposing a public PluginMessages constructor")
    args = parser.parse_args()
    import launcher_capabilities
    lanes={"ingestion":args.ingest_plugins or args.plugins}
    if args.query_plugins: lanes["query"]=args.query_plugins
    if not set(args.reporting_plugins)<=set(cls for lane in lanes.values() for cls in lane): parser.error("Reporting classes must be selected pipeline plugins.")
    capabilities={"adapters":[],"subscriptions":[],"host":{"type":"micronaut-web" if args.web else "cli","port":8080,"openBrowser":False},"template":{"id":args.template,"version":"1","settings":{}}}
    if args.ui: capabilities["adapters"].append({"id":"ui","type":"ui","version":"1","config":{}})
    if args.kafka_bootstrap_servers: capabilities["adapters"].append({"id":"kafka","type":"kafka","version":"1","config":{"bootstrapServers":args.kafka_bootstrap_servers,"topic":args.kafka_topic,"securityProtocol":args.kafka_security_protocol,"username":args.kafka_username_ref,"password":args.kafka_password_ref}})
    for adapter in capabilities["adapters"]:
        for mode in lanes: capabilities["subscriptions"].append({"adapterId":adapter["id"],"workflow":"QUERY_PROCESSING" if mode=="query" else "DOCUMENT_INGESTION","nodeIds":[],"eventTypes":[],"severities":[]})
    try:
        custom_template=launcher_capabilities.read_template(args.template_bundle,args.trusted_template) if args.template_bundle else None
        if custom_template: capabilities["template"]={"id":custom_template["id"],"version":"1","settings":{}}
        if args.capabilities:
            if args.capabilities.stat().st_size>256000: raise ValueError("Capability settings exceed 256 KB.")
            capabilities=json.loads(args.capabilities.read_text(encoding="utf-8"))
        requested_template=capabilities.get("template",{}).get("id","standard")
        custom_template=launcher_capabilities.resolve_template(requested_template,custom_template)
        if custom_template and requested_template in (custom_template["manifest"]["id"],custom_template["id"]):
            capabilities.setdefault("template",{"version":"1"})["id"]=custom_template["id"]
            settings=launcher_capabilities.template_settings.defaults(custom_template["manifest"].get("settingsSchema"))
            settings.update(capabilities["template"].get("settings",{}));capabilities["template"]["settings"]=settings
        launcher_capabilities.validate(capabilities,lanes,custom_template)
    except (OSError,ValueError,TypeError,KeyError) as error: parser.error(str(error))

    if args.query_plugins and not args.ingest_plugins:
        parser.error("--query-plugins requires --ingest-plugins")
    if args.ingest_plugins and not args.query_plugins:
        parser.error("RAG launcher requires both --ingest-plugins and --query-plugins")

    validate_identifier(args.project_name, "project name")
    validate_identifier(args.main_class, "main class")
    if args.main_class.casefold()=="pipelineassembly": parser.error("Main class must not collide with PipelineAssembly.")
    package_parts = args.package.split(".")
    if any(not JAVA_IDENTIFIER.fullmatch(part) for part in package_parts):
        parser.error(f"invalid Java package name: {args.package}")

    output = args.output.expanduser().absolute()
    for candidate in (output, *output.parents):
        if candidate.is_symlink() or getattr(os.path,"isjunction",lambda value:False)(str(candidate)): parser.error("Output and its parents cannot be symlinks or junctions.")
    project_dir = output.resolve() / args.project_name
    if project_dir.is_symlink() or project_dir.exists() and any(project_dir.iterdir()): parser.error(f"Project folder must be empty or absent: {project_dir}")
    java_dir = project_dir / "src" / "main" / "java" / Path(*package_parts)
    java_dir.mkdir(parents=True, exist_ok=True)
    java_file = java_dir / f"{args.main_class}.java"
    pom_file = project_dir / "pom.xml"
    env_file = project_dir / "rag-env.bat"
    powershell_env_file = project_dir / "rag-env.ps1"
    if java_file.exists() or pom_file.exists():
        parser.error(f"refusing to overwrite existing generated project: {project_dir}")

    source, assembly = launcher_capabilities.render(args.package,args.main_class,lanes,capabilities,args.reporting_plugins)
    java_file.write_text(source, encoding="utf-8")
    (java_dir/"PipelineAssembly.java").write_text(assembly,encoding="utf-8")
    launcher_capabilities.bundle(project_dir,capabilities,lanes,custom_template)
    dependencies = list(args.dependency)
    if args.ingest_plugins:
        rag_dependencies = [
            ("com.core.docintel", "pdf-ocr-to-text-plugin", "1.0.0"),
            ("com.core.docintel", "citation-text-splitter-plugin", "1.0.0"),
            ("com.core.docintel", "ollama-embedding-plugin", "1.0.0"),
            ("com.core.docintel", "neo4j-writer-plugin", "1.0.0"),
            ("com.core.docintel", "neo4j-retrieval-plugin", "1.0.0"),
            ("com.core.docintel", "ollama-answer-plugin", "1.0.0"),
        ]
        dependencies = list(dict.fromkeys(dependencies + rag_dependencies))
    if capabilities.get("host",{}).get("type")=="micronaut-web": dependencies.append(("com.core.docintel","pipeline-web","1.0.0"))
    if any(a["type"]=="kafka" for a in capabilities.get("adapters",[])): dependencies.append(("com.core.docintel","pipeline-event-kafka","1.0.0"))
    pom=render_pom(args.package,args.main_class,args.project_name,list(dict.fromkeys(dependencies)),capabilities.get("host",{}).get("type")=="micronaut-web")
    if any(a["type"]=="kafka" for a in capabilities.get("adapters",[])): pom=re.sub(r"  <profiles>.*?</profiles>","",pom,flags=re.S)
    pom_file.write_text(pom,encoding="utf-8")
    env_file.write_text(render_env_batch(), encoding="utf-8")
    powershell_env_file.write_text(render_env_powershell(), encoding="utf-8")
    print(f"Created: {project_dir}")
    print(f"Java:    {java_file}")
    print(f"Maven:   {pom_file}")
    print(f"Env:     {env_file}")
    print(f"Env:     {powershell_env_file}")
    if args.ingest_plugins:
        print("RAG dependencies were added automatically. Build with 'mvn clean package'; use 'mvn -Pnative clean package' for GraalVM Native Image.")
    else:
        print("Add Maven dependencies for the listed plugin classes, then run 'mvn clean package' or 'mvn -Pnative clean package'.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
