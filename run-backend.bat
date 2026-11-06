@echo off
setlocal
pushd "%~dp0"

if not defined JAVA_HOME if exist "D:\Softwares\Installed\JAVA\GraalVM\sdkman\candidates\java\current\bin\java.exe" set "JAVA_HOME=D:\Softwares\Installed\JAVA\GraalVM\sdkman\candidates\java\current"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
where java.exe >nul 2>nul
if errorlevel 1 (
  echo Java is required. Set JAVA_HOME or add java.exe to PATH.
  popd
  exit /b 1
)

if not defined PORT set "PORT=8080"
if not defined BIND_ADDRESS set "BIND_ADDRESS=127.0.0.1"
if not defined PLUGINTELL_PLUGIN_ROOT set "PLUGINTELL_PLUGIN_ROOT=%CD%\plugins"
if not defined PLUGINTELL_DB_PATH set "PLUGINTELL_DB_PATH=%CD%\data\plugintell.db"
if not defined PIPELINE_INPUT_ROOT set "PIPELINE_INPUT_ROOT=%CD%\data\ingestion"

if not exist "%PIPELINE_INPUT_ROOT%" mkdir "%PIPELINE_INPUT_ROOT%"

if not exist "pipeline-builder-api\target\pipeline-builder-api.jar" (
  echo Built application JAR not found. Run build.bat first.
  popd
  exit /b 1
)

echo Starting backend at %BIND_ADDRESS%:%PORT%...
echo Studio URL: http://127.0.0.1:%PORT%/ui/studio.html
java -jar "pipeline-builder-api\target\pipeline-builder-api.jar"
set "RUN_RESULT=%ERRORLEVEL%"
echo Backend stopped with exit code %RUN_RESULT%.
popd
exit /b %RUN_RESULT%
