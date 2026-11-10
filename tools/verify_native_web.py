"""Run the exported offline NativeWeb fixture as an executable, without its JARs.

Export it with RagProjectTest#nativeWebGenerationIncludesHostAssetsAndDirectExecutableLaunchers
and -Dpipeline.native-smoke-dir=<absolute-empty-folder>, then build -Pnative.
This deliberately uses fixture providers, not live Ollama/Neo4j services.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def verify(project):
    executable = project / "target" / ("NativeWeb.exe" if os.name == "nt" else "NativeWeb")
    if not executable.is_file():
        raise ValueError("Build the exported NativeWeb fixture with -Pnative first.")
    port = int((project / "native-smoke-port.txt").read_text())
    descriptor=json.loads((project / "src/main/resources/pipeline-descriptor.json").read_text(encoding="utf-8"))
    template=descriptor.get("capabilities",{}).get("template",{}).get("id","water")
    work = project / "target" / ("isolated native " + uuid.uuid4().hex[:8])
    work.mkdir()
    (work / "target").mkdir()
    binary = work / "target" / executable.name
    shutil.copy2(executable, binary)
    shutil.copy2(project / "run.bat", work / "run.bat")
    inputs = work / "data" / "ingestion"
    inputs.mkdir(parents=True)
    (inputs / "sample.txt").write_text("Synthetic native web fixture input.", encoding="utf-8")
    env = os.environ.copy()
    env["JAVA_HOME"] = str(work / "no-java-installation")
    env["PIPELINE_INPUT_ROOT"] = str(inputs)
    env["PIPELINE_LOG_DIR"] = str(work / "logs")
    if os.name == "nt":
        env["PATH"] = os.path.join(env.get("SystemRoot", "C:/Windows"), "System32")
    else:
        env["PATH"] = "/usr/bin:/bin"
    origin = "http://127.0.0.1:" + str(port)

    def request(path, body=None, headers=None):
        return urllib.request.urlopen(urllib.request.Request(origin + path,
            data=None if body is None else json.dumps(body).encode(),
            headers={"Content-Type": "application/json", **(headers or {})}), timeout=10)

    def get(path):
        with request(path) as response:
            return json.load(response)

    def first_frame(path, headers=None):
        with request(path, headers=headers) as response:
            frame = {}
            for _ in range(100):
                line = response.readline().decode().rstrip("\r\n")
                if not line and "data" in frame:
                    frame["data"] = json.loads(frame["data"])
                    return frame
                if ":" in line:
                    key, value = line.split(":", 1)
                    frame[key] = value.lstrip()
        raise AssertionError("No SSE frame arrived")

    report = {"isolatedDirectory": str(work), "fixtureProviders": True}
    with (work / "console.log").open("w", encoding="utf-8") as console:
        server = subprocess.Popen([str(binary), "serve"], cwd=work, env=env,
            stdout=console, stderr=subprocess.STDOUT)
        try:
            ready = False
            for _ in range(300):
                assert server.poll() is None, "Native server exited; inspect " + str(work / "console.log")
                try:
                    application = get("/api/v1/application")
                    ready = True
                    break
                except (OSError, ValueError):
                    time.sleep(.1)
            assert ready, "Native host did not become ready"
            assert set(application["modes"]) == {"ingestion", "query"}
            for asset in ("monitor.html", "monitor.js", "monitor.css", "template-bridge.js"):
                with request("/ui/" + asset) as response:
                    content = response.read()
                    assert response.status == 200 and content
                    if asset == "monitor.html":
                        assert b'host-answer' in content
            assert {item["id"] for item in get("/api/v1/templates")} >= {"standard", "water"}
            if template not in ("standard","water"):
                catalog=get("/api/v1/templates")
                selected=next(item for item in catalog if item["id"]==template)
                assert "settingsSchema" in selected
                with request(selected["rendererUrl"]) as response:
                    assert "sandbox allow-scripts" in response.headers["Content-Security-Policy"]
                    assert b"data-pipeline-inspector" in response.read()
                with request("/ui/templates/"+template+"/styles.css") as response:
                    assert response.status==200 and b"prefers-reduced-motion" in response.read()
                manifest=get("/ui/templates/"+template+"/manifest.json")
                for asset in manifest.get("assets",[]):
                    with request("/ui/templates/"+template+"/"+asset) as response:
                        assert response.status==200 and response.read()
                report["templateAssetCount"]=len(manifest.get("assets",[]))
            runs = []
            for mode in ("ingestion", "query", "query"):
                with request("/api/v1/executions", {"mode": mode, "input": "sample.txt" if mode == "ingestion" else "sample question"}) as response:
                    assert response.status == 202
                    run = json.load(response)["executionId"]
                for _ in range(300):
                    snapshot = get("/api/v1/executions/" + run + "/monitor")
                    if snapshot["status"] in ("SUCCEEDED", "FAILED", "CANCELLED"):
                        break
                    time.sleep(.05)
                assert snapshot["status"] == "SUCCEEDED", snapshot
                assert snapshot["template"]["id"] == template
                with request("/api/v1/executions/" + run + "/result") as response:
                    assert response.headers["Cache-Control"] == "no-store"
                    result = json.load(response)
                if mode == "query":
                    assert result["answer"] == "UAE answer <script> rendered as text"
                    assert result["citations"][0]["sourceName"] == "Middle East.pdf"
                    assert result["citations"][0]["pageNumber"] == 7
                    assert "private source text" not in json.dumps(result)
                    assert "UAE answer" not in json.dumps(snapshot)
                else:
                    assert result["available"] is False
                events = get("/api/v1/executions/" + run + "/events?afterSequence=0")
                sequences = [event["sequence"] for event in events]
                assert sequences == list(range(1, len(events) + 1))
                assert len({event["eventId"] for event in events}) == len(events)
                assert any(event["type"] == "RUN_SUCCEEDED" for event in events)
                frame = first_frame("/api/v1/executions/" + run + "/stream?afterSequence=0", {"Last-Event-ID": "1"})
                assert frame["event"] == "execution" and frame["id"] == "2"
                assert frame["data"]["runId"] == run
                gap = first_frame("/api/v1/executions/" + run + "/stream?afterSequence=999999")
                assert gap["event"] == "gap" and gap["data"]["runId"] == run
                runs.append(run)
            history = get("/api/v1/executions")
            assert {item["runId"] for item in history} == set(runs)
            assert "UAE answer" not in json.dumps(history)
            for path, body, expected in (("/api/v1/pipelines", None, 404),
                ("/api/v1/executions", {"mode": "ingestion", "input": "../outside.txt"}, 400)):
                try:
                    request(path, body).close()
                    raise AssertionError("Expected rejection: " + path)
                except urllib.error.HTTPError as error:
                    assert error.code == expected
            log = (work / "logs" / "server-0.log").read_text(encoding="utf-8")
            assert "RUN_STARTED" in log and "RUN_SUCCEEDED" in log
            assert all(run in log for run in runs)
            assert "UAE answer" not in log and "private source text" not in log
            report.update(success=True, runIds=runs, uiAssets=True, answerAndCitations=True,
                sseReplay=True, explicitHistoryGap=True, fileLogs=True, javaAndExternalJarsAbsent=True,template=template)
        finally:
            server.terminate()
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait(timeout=5)
    if os.name == "nt":
        help_result = subprocess.run(["cmd.exe", "/d", "/c", "call", str(work / "run.bat"), "--help"],
            cwd=project, env=env, capture_output=True, text=True, timeout=15)
        assert help_result.returncode == 0 and "serve" in help_result.stdout, help_result.stderr
        failure = subprocess.run(["cmd.exe", "/d", "/c", "call", str(work / "run.bat"), "--unknown"],
            cwd=project, env=env, capture_output=True, text=True, timeout=15)
        assert failure.returncode != 0
        report["nativeBatchHelpAndExitCode"] = True
    (work / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("project", type=Path)
    verify(parser.parse_args().project.resolve())
