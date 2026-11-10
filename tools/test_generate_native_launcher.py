import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"tools"))
import launcher_capabilities

CLASSES="com.core.docintel.plugins.ExtractTextPlugin,com.core.docintel.plugins.StaticSplitterPlugin,com.core.docintel.plugins.TestEmbedderPlugin,com.core.docintel.plugins.TestWriterPlugin"

class GeneratorTests(unittest.TestCase):
    def setUp(self):
        (ROOT/"target").mkdir(exist_ok=True)
        self.directory=tempfile.TemporaryDirectory(prefix="python-generator-",dir=ROOT/"target")
        self.addCleanup(self.directory.cleanup)
        self.root=Path(self.directory.name).resolve()
        assert self.root.is_relative_to((ROOT/"target").resolve())

    def generate(self,name,*extra,success=True):
        command=[sys.executable,str(ROOT/"tools/generate_native_launcher.py"),"--plugins",CLASSES,"--output",str(self.root),"--project-name",name,"--main-class","Main","--package","generated.smoke",*extra]
        result=subprocess.run(command,capture_output=True,text=True,timeout=30)
        self.assertEqual(result.returncode,0 if success else 2,result.stdout+result.stderr)
        return self.root/name

    def test_minimal_compiles_and_executes_without_server_dependencies(self):
        project=self.generate("Minimal")
        pom=(project/"pom.xml").read_text()
        self.assertNotIn("pipeline-web",pom);self.assertNotIn("kafka",pom);self.assertNotIn("micronaut",pom)
        source=(project/"src/main/java/generated/smoke/PipelineAssembly.java").read_text()
        self.assertIn("new com.core.docintel.plugins.TestEmbedderPlugin(events.prepareAttempt",source)
        self.assertNotIn("Class.forName",source);self.assertNotIn("ServiceLoader",source)
        jars=[ROOT/"pipeline-core/target/pipeline-core.jar"]+[ROOT/"plugins"/name/"target"/(name+"-plugin.jar") for name in ("extract-text","static-splitter","test-embedder","test-writer")]
        java_home=Path(os.environ["JAVA_HOME"])/"bin"
        classes=project/"target/classes";classes.mkdir(parents=True)
        sources=list((project/"src/main/java").rglob("*.java"));classpath=os.pathsep.join(map(str,jars))
        subprocess.run([str(java_home/"javac"),"--release","21","-proc:none","-cp",classpath,"-d",str(classes),*map(str,sources)],check=True,capture_output=True,timeout=60)
        sample=project/"sample.txt";sample.write_text("A document used only by the local fixture.",encoding="utf-8")
        result=subprocess.run([str(java_home/"java"),"-cp",str(classes)+os.pathsep+classpath,"generated.smoke.Main",str(sample)],capture_output=True,text=True,timeout=30)
        self.assertEqual(0,result.returncode,result.stdout+result.stderr);self.assertIn("Pipeline completed successfully",result.stdout)
        help_result=subprocess.run([str(java_home/"java"),"-cp",str(classes)+os.pathsep+classpath,"generated.smoke.Main","--help"],capture_output=True,text=True,timeout=10)
        self.assertNotIn("serve",help_result.stdout)

    def test_web_is_selected_explicitly_and_ui_requires_host(self):
        self.generate("Rejected","--ui",success=False)
        project=self.generate("Web","--web","--ui","--template","water")
        self.assertIn("pipeline-web",(project/"pom.xml").read_text())
        import xml.etree.ElementTree as ET
        expected=ET.parse(ROOT/"pom.xml").findtext("{*}properties/{*}netty.version")
        actual=ET.parse(project/"pom.xml").findtext("{*}dependencyManagement/{*}dependencies/{*}dependency/{*}version")
        self.assertEqual(expected,actual,"Generated web dependencies must use the reactor's Netty version")
        self.assertIn("<id>native</id>",(project/"pom.xml").read_text())
        self.assertIn("<version>0.10.6</version>",(project/"pom.xml").read_text())
        self.assertIn("--initialize-at-run-time=io.netty,org.slf4j",(project/"pom.xml").read_text())
        native=(project/"run-native.bat").read_text()
        self.assertIn('"target\\web.exe" %*',native)
        self.assertNotIn(' -jar ',native)
        self.assertIn('run-native.bat serve',native)
        self.assertIn('exec ./target/web "$@"',(project/"run-native.sh").read_text())
        self.assertTrue((project/"src/main/resources/public/monitor.js").is_file())
        self.assertIn('id="host-answer"',(project/"src/main/resources/public/monitor.html").read_text())
        self.assertIn('/result',(project/"src/main/resources/public/monitor.js").read_text())
        self.assertTrue((project/"src/main/resources/public/template-bridge.js").is_file())
        source=(project/"src/main/java/generated/smoke/Main.java").read_text()
        self.assertIn("new com.core.docintel.pipeline.web.MicronautWebAppAdapter",source)
        self.assertIn('equals("serve")',source)

    def test_kafka_wiring_is_explicit_and_does_not_include_web(self):
        project=self.generate("Kafka","--kafka-bootstrap-servers","localhost:9092")
        pom=(project/"pom.xml").read_text()
        self.assertIn("pipeline-event-kafka",pom);self.assertNotIn("pipeline-web",pom)
        self.assertIn("new com.core.docintel.pipeline.kafka.KafkaEventAdapter",(project/"src/main/java/generated/smoke/PipelineAssembly.java").read_text())
        self.assertFalse((project/"src/main/resources/public").exists())
        self.assertNotIn("<id>native</id>",pom)
        self.assertFalse((project/"run-native.bat").exists())

    def test_generated_start_scripts_list_only_selected_modes_and_web_logging(self):
        self.root=self.root/"projects with spaces";self.root.mkdir()
        for name,options,web in (("LaunchCli",(),False),("LaunchWeb",("--web","--ui"),True)):
            project=self.generate(name,*options)
            script=(project/"run.bat").read_text();readme=(project/"README.md").read_text()
            self.assertIn('pushd "%~dp0"',script);self.assertIn('exit /b %PIPELINE_EXIT%',script)
            self.assertEqual(web,'run.bat serve' in readme);self.assertEqual(web,'echo   run.bat serve' in script)
            self.assertEqual(web,'logs/server-0.log' in readme)
            self.assertEqual(web,'Query answer' in readme)
            if os.name=='nt':
                result=subprocess.run(['cmd.exe','/d','/c','call',str(project/'run.bat')],cwd=self.root,capture_output=True,text=True,timeout=10)
                self.assertEqual(0,result.returncode,result.stdout+result.stderr);self.assertIn('Usage:',result.stdout);self.assertEqual(web,'run.bat serve' in result.stdout)

    def test_custom_reporting_factory_is_explicit_and_restricted_to_selected_classes(self):
        with self.assertRaises(ValueError):launcher_capabilities.render("generated.smoke","Main",{"ingestion":["example.Plugin();Injected.run()"]},{})
        source,assembly=launcher_capabilities.render("generated.smoke","Main",{"ingestion":["example.ComposedPlugin"]},{},["example.ComposedPlugin"])
        self.assertIn('new example.ComposedPlugin(events.prepareAttempt(',assembly)
        self.assertNotIn('Class.forName',source+assembly)
        self.generate("InvalidFactory","--reporting-plugins","example.NotSelected",success=False)

    def test_custom_bundle_rejects_traversal_and_bundles_live_declarative_bridge(self):
        archive=self.root/"template.zip"
        manifest={"schemaVersion":1,"id":"sample","name":"Sample","version":"1","html":"index.html","css":"styles.css","executable":False,"assets":[]}
        with zipfile.ZipFile(archive,"w") as z:
            z.writestr("manifest.json",json.dumps(manifest));z.writestr("index.html",'<p data-pipeline-status></p>');z.writestr("styles.css","p {color:blue}")
        bundle=launcher_capabilities.read_template(archive)
        project=self.generate("Custom","--web","--ui","--template-bundle",str(archive))
        html=(project/"src/main/resources/public/templates"/bundle["id"]/"index.html").read_text()
        self.assertIn("/ui/template-bridge.js",html)
        with zipfile.ZipFile(archive,"a") as z:z.writestr("../outside.txt","bad")
        with self.assertRaises(ValueError):launcher_capabilities.read_template(archive)

    def test_raw_credentials_and_unknown_subscription_nodes_are_rejected(self):
        for invalid in ({"template":{"id":None}},{"host":{"type":"micronaut-web","openBrowser":"true"}}):
            with self.assertRaises(ValueError):launcher_capabilities.validate(invalid,{"ingestion":CLASSES.split(",")})
        caps={"adapters":[{"id":"kafka","type":"kafka","version":"1","config":{"bootstrapServers":"localhost:9092","topic":"events","password":"unsafe-literal"}}]}
        with self.assertRaises(ValueError):launcher_capabilities.validate(caps,{"ingestion":CLASSES.split(",")})

        caps={"adapters":[{"id":"ui","type":"ui","version":"1"}],"host":{"type":"micronaut-web"},"subscriptions":[{"adapterId":"ui","workflow":"DOCUMENT_INGESTION","nodeIds":["unknown"]}]}
        with self.assertRaises(ValueError):launcher_capabilities.validate(caps,{"ingestion":CLASSES.split(",")})

    def test_rich_bundled_templates_use_content_pins_and_copy_only_the_selected_view(self):
        for theme in ("aurora","midnight"):
            bundle=launcher_capabilities.resolve_template(theme)
            project=self.generate("Theme"+theme,"--web","--ui","--template",theme)
            descriptor=json.loads((project/"src/main/resources/pipeline-descriptor.json").read_text())
            self.assertEqual(bundle["id"],descriptor["capabilities"]["template"]["id"])
            selected=project/"src/main/resources/public/templates"
            self.assertEqual([bundle["id"]],[path.name for path in selected.iterdir()])
            self.assertIn("data-pipeline-inspector",(selected/bundle["id"]/"index.html").read_text(encoding="utf-8"))
            self.assertEqual(True,descriptor["capabilities"]["template"]["settings"]["showHealth"])

    def test_external_template_schema_rejects_invalid_settings_and_preserves_boolean_false(self):
        bundle=launcher_capabilities.resolve_template("aurora")
        caps={"host":{"type":"micronaut-web"},"adapters":[{"id":"ui","type":"ui","version":"1"}],"template":{"id":bundle["id"],"version":"1","settings":{"showTimeline":False,"accent":"#112233"}}}
        launcher_capabilities.validate(caps,{"ingestion":CLASSES.split(",")},bundle)
        for invalid in ({"accent":"javascript:bad"},{"showTimeline":"false"},{"timelineLimit":101},{"unknown":True}):
            caps["template"]["settings"]=invalid
            with self.assertRaises(ValueError):launcher_capabilities.validate(caps,{"ingestion":CLASSES.split(",")},bundle)

if __name__=="__main__":
    unittest.main()

