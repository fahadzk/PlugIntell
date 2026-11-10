package com.core.docintel.pipeline.builder;

import java.util.*;

/** Capability-aware launch help. Values are validated project names and fixed commands. */
final class GeneratedLaunchFiles {
    private GeneratedLaunchFiles(){}
    private static List<String> commands(PipelineDefinition definition){
        List<String> result=new ArrayList<>();
        if(ProjectCapabilities.web(definition))result.add("serve");
        if(definition.hasWorkflow(PipelineType.DOCUMENT_INGESTION))result.add("--mode ingestion --input \"document.txt\"");
        if(definition.hasWorkflow(PipelineType.QUERY_PROCESSING))result.add("--mode query --query \"Your question\"");
        result.add("--help");return result;
    }
    static String batch(String name,PipelineDefinition definition){return batch(name,definition,false);}
    static String batch(String name,PipelineDefinition definition,boolean nativeImage){
        String launch=nativeImage?"\"target\\"+name+".exe\" %*":"set \"PIPELINE_JAVA=java\"\r\nif defined JAVA_HOME set \"PIPELINE_JAVA=%JAVA_HOME%\\bin\\java.exe\"\r\n\"%PIPELINE_JAVA%\" -jar \"target\\"+name.toLowerCase(Locale.ROOT)+"-1.0.0.jar\" %*";
        StringBuilder script=new StringBuilder("@echo off\r\nsetlocal\r\npushd \"%~dp0\" || exit /b 1\r\nif \"%~1\"==\"\" goto usage\r\n"+launch+"\r\nset \"PIPELINE_EXIT=%ERRORLEVEL%\"\r\npopd\r\nexit /b %PIPELINE_EXIT%\r\n:usage\r\necho Usage:\r\n");
        commands(definition).forEach(command->script.append("echo   run.bat ").append(command).append("\r\n"));
        if(ProjectCapabilities.web(definition))script.append("echo Web mode keeps this console open; server execution logs are also in logs\\server-0.log.\r\n");
        return script.append("popd\r\nexit /b 0\r\n").toString();
    }
    static String shell(String name,PipelineDefinition definition){return shell(name,definition,false);}
    static String shell(String name,PipelineDefinition definition,boolean nativeImage){
        StringBuilder script=new StringBuilder("#!/bin/sh\ncd \"$(dirname \"$0\")\" || exit 1\nif [ \"$#\" -eq 0 ]; then\ncat <<'USAGE'\nUsage:\n");
        commands(definition).forEach(command->script.append("  ./run.sh ").append(command).append('\n'));
        script.append("USAGE\nexit 0\nfi\n");
        if(nativeImage)return script.append("exec ./target/").append(name).append(" \"$@\"\n").toString();
        return script.append("PIPELINE_JAVA=java\nif [ -n \"${JAVA_HOME:-}\" ]; then PIPELINE_JAVA=\"$JAVA_HOME/bin/java\"; fi\nexec \"$PIPELINE_JAVA\" -jar target/").append(name.toLowerCase(Locale.ROOT)).append("-1.0.0.jar \"$@\"\n").toString();
    }
    static String instructions(String name,PipelineDefinition definition,boolean nativeImage){
        String artifact="target/"+name.toLowerCase(Locale.ROOT)+"-1.0.0.jar";
        StringBuilder text=new StringBuilder("\n## Run options\n\n");
        text.append(nativeImage?"The native executable contains the selected code and UI resources; Java and lib/ are not needed to run it. Keep lib/ for rebuilding. Keep any DLL/shared-library files emitted by GraalVM beside the executable, especially for document parsers using AWT/image codecs. Download the project ZIP to retain these runtime files; the executable-only download does not contain them. ":"Keep `lib/` beside `target/`. ");
        text.append("Run `run.bat` without arguments to list available commands. Scripts run from this project directory.\n\n```bat\n");
        commands(definition).forEach(command->text.append("run.bat ").append(command).append('\n'));
        text.append("```\n\nOn Linux/macOS use `sh run.sh` with the same arguments. ");
        if(nativeImage){text.append("Direct native commands on Windows:\n\n```bat\n");commands(definition).forEach(command->text.append("target\\").append(name).append(".exe ").append(command).append('\n'));text.append("```\n\nOn Linux/macOS the executable is `./target/").append(name).append("`. Build on the target OS with GraalVM JDK 21, Maven and its supported native compiler: `mvn -Pnative package`.\n");}
        else{text.append("Direct Java commands:\n\n```shell\n");commands(definition).forEach(command->text.append("java -jar ").append(artifact).append(' ').append(command).append('\n'));text.append("```\n");}
        if(ProjectCapabilities.web(definition))text.append("\n## Web UI and server logs\n\n`run.bat serve` starts the Micronaut web host and keeps it running across ingestion/query runs until Ctrl+C. Open the printed local URL. The selected Local UI template and its public settings are frozen from the published pipeline. External HTML/CSS assets are under src/main/resources/public/templates/; the descriptor records the content pin. Configure a different view in Studio UI templates, save, publish and regenerate. Editing the descriptor alone does not change compiled wiring. Native builds embed these resources and require rebuilding after changes. The generated page displays the final query answer and citation source/page metadata in **Query answer** after success. Open a historical execution to restore its answer. Answers are fetched separately from telemetry and are never sent to custom templates or structured logs. Retention is memory-only: at most 100 runs, 262,144 answer characters and 100 citations per result; eviction or restart removes results. Citation document text is excluded. AnswerResult and plain String query outputs are supported; other output types keep their safe monitor summaries. Studio uses its own server; the Local UI adapter in this generated project requires this selected host.\n\nPut ingestion files in `data/ingestion` (create that directory if needed), or set `PIPELINE_INPUT_ROOT` to another directory before starting. Web inputs are relative to that root.\n\nServer startup and structured run/node/attempt/progress/failure logs appear in the same terminal and in `logs/server-0.log`, rotating across three files of 5 MB each. Set `PIPELINE_LOG_DIR` to change the directory. Logs include run IDs so concurrent runs can be distinguished. Direct plugin stdout/stderr remains in the terminal; redirect both streams if you also need that output in one file. Log payloads omit documents, prompts, answers and embedding arrays.\n\nFor example, in Command Prompt: `mkdir logs` then `run.bat serve > logs\\console.log 2>&1`. To follow the structured file in PowerShell, use `Get-Content .\\logs\\server-0.log -Wait -Tail 100`.\n");
        else text.append("\nThis is a command-line application. Web hosting was not selected.\n");
        return text.append("\nOnly nonempty workflows are included. Node settings are embedded per workflow; environment secret references resolve at runtime. Add `--debug` to CLI execution for redacted diagnostics.\n").toString();
    }
}
