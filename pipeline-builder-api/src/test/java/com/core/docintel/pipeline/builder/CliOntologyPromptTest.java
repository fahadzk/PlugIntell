package com.core.docintel.pipeline.builder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class CliOntologyPromptTest {
    @TempDir Path temporary;
    @Test void generatedCliAsksForInstructionsAndHonorsDefaultAndUnattendedModes()throws Exception{
        Path source=temporary.resolve("src/generated/pipeline"),classes=temporary.resolve("classes");Files.createDirectories(source);Files.createDirectories(classes);
        try(var resource=getClass().getResourceAsStream("/generated/CliSupport.java.txt")){Files.copy(resource,source.resolve("CliSupport.java"));}
        var method=PipelineProjectGenerator.class.getDeclaredMethod("runtimeSource");method.setAccessible(true);Files.writeString(source.resolve("RuntimeSupport.java"),(String)method.invoke(null));
        Files.writeString(source.resolve("Probe.java"),"""
            package generated.pipeline;
            import java.util.*;
            public class Probe {
              public static void main(String[] args) {
                String mode=args[0];CliSupport.enabled=!mode.equals("unattended");CliSupport.cliMode=!mode.equals("web");
                var config=new HashMap<String,Object>(Map.of("GRAPH_LLM_URL","http://localhost:1","GRAPH_PROMPT","","GRAPH_SCHEMA_JSON",""));
                if(mode.equals("supplied"))config.put("GRAPH_PROMPT","Saved ontology instructions");
                if(mode.equals("schema"))config.put("GRAPH_SCHEMA_JSON","{}");
                if(mode.equals("disabled")){config.put("GRAPH_GENERATE_ONTOLOGY",false);CliSupport.enabled=false;}
                if(mode.equals("toggleOff"))config.put("GRAPH_LLM_API_KEY","secret://env/UNUSED_ONTOLOGY_KEY");
                var result=CliSupport.ontologySettings("gliner-graph-extraction",config);
                System.out.println("SELECTED="+result.get("GRAPH_PROMPT"));
                System.out.println("GENERATE="+result.get("GRAPH_GENERATE_ONTOLOGY"));
                System.out.println("HAS_LLM_KEY="+result.containsKey("GRAPH_LLM_API_KEY"));
                System.out.println("SCHEMA="+result.get("GRAPH_SCHEMA_JSON"));
              }
            }
            """);
        String core=new PluginCatalog("").pipelineCoreJar().toAbsolutePath().toString();
        List<String> arguments=new ArrayList<>(List.of("--release","21","-proc:none","-classpath",core,"-d",classes.toString()));try(var files=Files.list(source)){files.forEach(f->arguments.add(f.toString()));}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,arguments.toArray(String[]::new)));
        String custom=run(classes,core,"interactive","y\nFocus on historical organizations and treaties.\n");assertTrue(custom.contains("Ontology instructions (Enter"));assertTrue(custom.contains("SELECTED=Focus on historical organizations and treaties."));assertTrue(custom.contains("GENERATE=true"));
        assertFalse(custom.contains("Want to express ontology manually"));
        String defaults=run(classes,core,"interactive","\n\n");assertTrue(defaults.contains("Ontology instructions (Enter"));assertTrue(defaults.contains("SELECTED=\n")||defaults.contains("SELECTED=\r\n"));
        String off=run(classes,core,"toggleOff","n\nn\n");assertFalse(off.contains("Ontology instructions (Enter"));assertTrue(off.contains("GENERATE=false"));assertTrue(off.contains("HAS_LLM_KEY=false"));assertTrue(off.contains("Want to express ontology manually [Y/n]?"));assertFalse(off.contains("Enter Ontology entity types:"));
        String manual=run(classes,core,"toggleOff","n\nY\nTrain,Station\nSTOPS_AT (Train -> Station), NEXT_STOP (Station -> Station)\n");
        assertManualRailwayOntology(manual);assertTrue(manual.contains("HAS_LLM_KEY=false"));assertFalse(manual.contains("Ontology instructions (Enter"));
        String recovered=run(classes,core,"toggleOff","n\ny\nTrain,train\nTrain,Station\nSTOPS_AT (Train -> Unknown)\nSTOPS_AT (Train -> Station), STOPS_AT (Train -> Station)\nSTOPS_AT Train -> Station\nSTOPS_AT (Train -> Station), NEXT_STOP (Station -> Station)\n");
        assertTrue(recovered.contains("Entity types must be unique."));assertTrue(recovered.contains("Relationship endpoints must reference declared entity types"));assertTrue(recovered.contains("Relationship types must be unique."));assertManualRailwayOntology(recovered);
        for(String mode:List.of("unattended","web","schema","supplied","disabled")){String output=run(classes,core,mode,mode.equals("supplied")?"y\n":"");assertFalse(output.contains("Ontology instructions (Enter"),mode);assertFalse(output.contains("Want to express ontology manually"),mode);if(mode.equals("supplied"))assertTrue(output.contains("SELECTED=Saved ontology instructions"));else assertFalse(output.contains("Generate ontology using the LLM?"),mode);if(mode.equals("disabled"))assertTrue(output.contains("GENERATE=false"));}
    }
    private void assertManualRailwayOntology(String output)throws Exception{
        assertTrue(output.contains("GENERATE=false"));
        String json=output.lines().filter(line->line.startsWith("SCHEMA=")).findFirst().orElseThrow().substring(7);
        var schema=new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertEquals("Manual",schema.path("domain").asText());assertEquals(2,schema.path("entities").size());
        assertEquals("Train",schema.path("entities").get(0).path("name").asText());assertEquals("Station",schema.path("entities").get(1).path("name").asText());
        assertEquals(2,schema.path("relationships").size());var stops=schema.path("relationships").get(0);
        assertEquals("STOPS_AT",stops.path("name").asText());assertEquals("Train",stops.path("head").asText());assertEquals("Station",stops.path("tail").asText());
        var next=schema.path("relationships").get(1);assertEquals("NEXT_STOP",next.path("name").asText());assertEquals("Station",next.path("head").asText());assertEquals("Station",next.path("tail").asText());
    }
    private String run(Path classes,String core,String mode,String input)throws Exception{
        Path log=temporary.resolve(mode+UUID.randomUUID()+".log");var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classes+java.io.File.pathSeparator+core,"generated.pipeline.Probe",mode).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{try(var stream=process.getOutputStream()){stream.write(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));}assertTrue(process.waitFor(15,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),Files.readString(log));return Files.readString(log);}finally{process.destroyForcibly();}
    }
}
