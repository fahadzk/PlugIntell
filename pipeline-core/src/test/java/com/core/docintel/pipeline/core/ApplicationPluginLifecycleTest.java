package com.core.docintel.pipeline.core;
import com.core.docintel.pipeline.api.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationPluginLifecycleTest {
    @TempDir Path directory;
    @AfterEach void clear(){PluginLoader.closeApplicationPlugins();}
    public static final class Scoped implements ApplicationScopedPlugin {
        public PluginManifest manifest(){return new PluginManifest("scoped","Scoped","1.0.0","1.0",getClass().getName(),"AnswerResult","AnswerResult");}
        public Object execute(Object input){return input;}public void close(){}public void closeApplication(){}
    }
    public static final class Ordinary implements Plugin {
        public PluginManifest manifest(){return new PluginManifest("ordinary","Ordinary","1.0.0","1.0",getClass().getName(),"AnswerResult","AnswerResult");}
        public Object execute(Object input){return input;}
    }
    private Path jar(Class<?> type,String id,String name)throws Exception{
        Path file=directory.resolve(id+".jar");try(var out=new JarOutputStream(Files.newOutputStream(file))){
            out.putNextEntry(new JarEntry("META-INF/PluginManifest.yaml"));out.write(("plugin:\n  id: "+id+"\n  name: "+name+"\n  version: 1.0.0\n  apiVersion: '1.0'\n  entrypoint: "+type.getName()+"\n  input: {type: AnswerResult}\n  output: {type: AnswerResult}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();
            String resource=type.getName().replace('.','/')+".class";out.putNextEntry(new JarEntry(resource));try(var in=type.getResourceAsStream("/"+resource)){in.transferTo(out);}out.closeEntry();
        }return file;
    }
    @Test void optInPluginsShareImplementationAcrossQueryLoadersWhileInstancesStayIndependent()throws Exception{
        Path file=jar(Scoped.class,"scoped","Scoped");Plugin first;try(var loader=new PluginLoader()){first=loader.load(file);}
        try(var loader=new PluginLoader()){var second=loader.load(file);assertNotSame(first,second);assertSame(first.getClass(),second.getClass());assertSame(first.getClass().getClassLoader(),second.getClass().getClassLoader());}
        Path ordinary=jar(Ordinary.class,"ordinary","Ordinary");try(var a=new PluginLoader();var b=new PluginLoader()){assertNotSame(a.load(ordinary).getClass(),b.load(ordinary).getClass());}
    }
    @Test void explicitTypeUnionsAreSubsetChecked(){
        assertTrue(PluginTypes.accepts("AnswerResult|RetrievalResult","AnswerResult"));assertTrue(PluginTypes.accepts("AnswerResult|RetrievalResult","RetrievalResult"));assertFalse(PluginTypes.accepts("AnswerResult|RetrievalResult","SearchRequest"));assertFalse(PluginTypes.accepts("AnswerResult","AnswerResult|RetrievalResult"));
    }
}
