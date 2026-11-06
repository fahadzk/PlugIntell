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

if not exist "pipeline-builder-api\target\pipeline-builder-api.jar" (
  echo No packaged application found. Building first...
  call build.bat
  if errorlevel 1 (
    popd
    exit /b 1
  )
)

if not defined PORT set "PORT=8080"
if not defined BIND_ADDRESS set "BIND_ADDRESS=127.0.0.1"
if not defined PLUGINTELL_PLUGIN_ROOT set "PLUGINTELL_PLUGIN_ROOT=%CD%\plugins"
if not defined PLUGINTELL_DB_PATH set "PLUGINTELL_DB_PATH=%CD%\data\plugintell.db"
if not defined PIPELINE_INPUT_ROOT set "PIPELINE_INPUT_ROOT=%CD%\data\ingestion"




start "PlugIntell RAG Studio Backend" "%ComSpec%" /k ""%~dp0run-backend.bat""
set "STUDIO_URL=http://127.0.0.1:%PORT%/ui/studio.html"
echo Waiting for the backend at %STUDIO_URL% ...
where curl.exe >nul 2>nul
if errorlevel 1 (
  timeout /t 8 /nobreak >nul
  goto :open
)
for /L %%I in (1,1,60) do (
  curl.exe --connect-timeout 1 -fsS "%STUDIO_URL%" -o nul >nul 2>nul
  if not errorlevel 1 goto :ready
  timeout /t 1 /nobreak >nul
)

echo The backend has not responded yet. You can still open %STUDIO_URL% when startup finishes.
goto :open

:ready
echo Backend is ready.
:open
start "" "%STUDIO_URL%"
echo The backend is running in its own console window. Close that window or press Ctrl+C there to stop it.
popd
exit /b 0
