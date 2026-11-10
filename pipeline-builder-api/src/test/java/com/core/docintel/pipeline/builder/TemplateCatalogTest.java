package com.core.docintel.pipeline.builder;
import com.core.docintel.pipeline.events.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TemplateCatalogTest {
    @TempDir Path temp;
    @Test void bundledExternalThemesHavePortablePinsSchemasAndDownloadableOriginalArchives()throws Exception{
        var catalog=new TemplateCatalog(temp.toString());var templates=catalog.list();assertEquals(4,templates.size());
        for(String name:List.of("aurora","midnight")){
            var descriptor=templates.stream().filter(template->template.get("id").toString().startsWith(name+"-")).findFirst().orElseThrow();String id=(String)descriptor.get("id");
            assertTrue(descriptor.containsKey("settingsSchema"));assertTrue(descriptor.containsKey("bundleUrl"));
            var bundle=TemplateBundle.read(catalog.archive(id),false);assertEquals(id,bundle.id());
            assertTrue(new String(bundle.files().get("index.html"),java.nio.charset.StandardCharsets.UTF_8).contains("data-pipeline-inspector"));
            assertEquals(id,catalog.importBundle(catalog.archive(id),false).get("id"));assertEquals(1,catalog.list().stream().filter(t->id.equals(t.get("id"))).count());
            var response=new CapabilityController(new AdapterService(),catalog).bundle(id);assertEquals("application/zip",response.getContentType().orElseThrow().toString());assertArrayEquals(catalog.archive(id),response.body());
        }
    }
    @Test void settingsAreValidatedAndSelectedBundledVersionsArePreservedOnDisk()throws Exception{
        var catalog=new TemplateCatalog(temp.toString());String id=catalog.list().stream().map(t->t.get("id").toString()).filter(value->value.startsWith("aurora-")).findFirst().orElseThrow();
        catalog.validatePin(Map.of("template",Map.of("id",id,"settings",Map.of("accent","#112233","showTimeline",false,"timelineLimit",10))));
        assertTrue(Files.isRegularFile(temp.resolve(id+".zip")));assertEquals(id,new TemplateCatalog(temp.toString()).get(id).id());
        for(var settings:List.of(Map.of("accent","blue"),Map.of("timelineLimit",1000),Map.of("showHealth","false"),Map.of("unknown","x")))assertThrows(IllegalArgumentException.class,()->catalog.validatePin(Map.of("template",Map.of("id",id,"settings",settings))));
        assertThrows(IllegalArgumentException.class,()->catalog.archive("../escape"));
    }
    @Test void changedArchiveCannotSilentlyReplaceAPublishedContentPin()throws Exception{
        var catalog=new TemplateCatalog(temp.toString());var themes=catalog.list().stream().filter(t->t.containsKey("bundleUrl")).toList();String id=(String)themes.get(0).get("id");
        Path imported=temp.resolve("imported");Files.createDirectories(imported);Files.write(imported.resolve("old-pin.zip"),catalog.archive((String)themes.get(1).get("id")));
        var other=new TemplateCatalog(imported.toString());assertThrows(IllegalArgumentException.class,()->other.get("old-pin"));
    }
}
