package com.core.docintel.pipeline.builder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class CliChatProviderPromptTest {
    @TempDir Path temporary;
    @Test void generatedCliSelectsChatProviderModelAndBaseUrlWithoutResolvingInactiveSecrets()throws Exception{
        Path source=temporary.resolve("src/generated/pipeline"),classes=temporary.resolve("classes");Files.createDirectories(source);Files.createDirectories(classes);
        try(var resource=getClass().getResourceAsStream("/generated/CliSupport.java.txt")){Files.copy(resource,source.resolve("CliSupport.java"));}
        var method=PipelineProjectGenerator.class.getDeclaredMethod("runtimeSource");method.setAccessible(true);Files.writeString(source.resolve("RuntimeSupport.java"),(String)method.invoke(null));
        Files.writeString(source.resolve("Probe.java"),"""
            package generated.pipeline;
            import java.util.*;
            public class Probe {
              public static void main(String[] args) {
                CliSupport.enabled=args[0].equals("interactive");CliSupport.cliMode=true;
                var input=new HashMap<String,Object>(Map.of("LLM_PROVIDER","OLLAMA","OLLAMA_CHAT_MODEL","existing-model","OPENAI_API_KEY","secret://env/OPENAI_API_KEY","GEMINI_API_KEY","secret://env/SHOULD_NOT_BE_RESOLVED","NEO4J_PASSWORD","secret://env/NEO4J_PASSWORD"));
                var result=CliSupport.text2CypherSettings(input,Map.of("OLLAMA_CHAT_MODEL","string","NEO4J_PASSWORD","string"));
                System.out.println("PROVIDER="+result.get("LLM_PROVIDER"));System.out.println("MODEL="+result.get(result.get("LLM_PROVIDER").equals("OLLAMA")?"OLLAMA_CHAT_MODEL":result.get("LLM_PROVIDER")+"_MODEL"));
                System.out.println("BASE="+result.get(result.get("LLM_PROVIDER")+"_BASE_URL"));System.out.println("HAS_GEMINI="+result.containsKey("GEMINI_API_KEY"));System.out.println("HAS_OLLAMA="+result.containsKey("OLLAMA_CHAT_MODEL"));
              }
            }
            """);
        String core=new PluginCatalog("").pipelineCoreJar().toAbsolutePath().toString();
        List<String> arguments=new ArrayList<>(List.of("--release","21","-proc:none","-classpath",core,"-d",classes.toString()));try(var files=Files.list(source)){files.forEach(f->arguments.add(f.toString()));}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,arguments.toArray(String[]::new)));
        String openai=run(classes,core,"interactive","OPENAI\ncustom-model\nhttp://localhost:1234/v1/\n");assertTrue(openai.contains("PROVIDER=OPENAI"));assertTrue(openai.contains("MODEL=custom-model"));assertTrue(openai.contains("BASE=http://localhost:1234/v1/"));assertTrue(openai.contains("HAS_GEMINI=false"));assertTrue(openai.contains("HAS_OLLAMA=false"));
        String ollama=run(classes,core,"interactive","\n\n\n");assertTrue(ollama.contains("PROVIDER=OLLAMA"));assertTrue(ollama.contains("MODEL=existing-model"));assertTrue(ollama.contains("BASE=http://localhost:11434"));assertTrue(ollama.contains("HAS_GEMINI=false"));
        String unattended=run(classes,core,"unattended","");assertFalse(unattended.contains("Chat provider ["));assertTrue(unattended.contains("PROVIDER=OLLAMA"));
    }
    private String run(Path classes,String core,String mode,String input)throws Exception{
        Path log=temporary.resolve(UUID.randomUUID()+".log");var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classes+java.io.File.pathSeparator+core,"generated.pipeline.Probe",mode).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{try(var stream=process.getOutputStream()){stream.write(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));}assertTrue(process.waitFor(15,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),Files.readString(log));return Files.readString(log);}finally{process.destroyForcibly();}
    }
}
