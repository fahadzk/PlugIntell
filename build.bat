@echo off
setlocal
pushd "%~dp0"

if not defined JAVA_HOME if exist "D:\Softwares\Installed\JAVA\GraalVM\sdkman\candidates\java\current\bin\javac.exe" set "JAVA_HOME=D:\Softwares\Installed\JAVA\GraalVM\sdkman\candidates\java\current"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
where javac.exe >nul 2>nul
if errorlevel 1 (
  echo Java JDK 21 is required. Set JAVA_HOME or add javac.exe to PATH.
  popd
  exit /b 1
)

set "MVN_CMD="
if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MVN_CMD=%MAVEN_HOME%\bin\mvn.cmd"
if not defined MVN_CMD for /f "delims=" %%M in ('where mvn.cmd 2^>nul') do if not defined MVN_CMD set "MVN_CMD=%%M"
if not defined MVN_CMD if exist "D:\Softwares\Installed\JAVA\apache-maven-3.10.0\bin\mvn.cmd" set "MVN_CMD=D:\Softwares\Installed\JAVA\apache-maven-3.10.0\bin\mvn.cmd"
if not defined MVN_CMD (
  echo Maven 3.9 or newer is required. Set MAVEN_HOME or add mvn.cmd to PATH.
  popd
  exit /b 1
)

echo Building PlugIntell RAG Studio and its plugin modules...
if defined MAVEN_REPO_LOCAL (
  call "%MVN_CMD%" "-Dmaven.repo.local=%MAVEN_REPO_LOCAL%" -DskipTests package
) else (
  call "%MVN_CMD%" -DskipTests package
)
set "BUILD_RESULT=%ERRORLEVEL%"
if not "%BUILD_RESULT%"=="0" (
  echo Build failed with exit code %BUILD_RESULT%.
  popd
  exit /b %BUILD_RESULT%
)
echo Build complete: pipeline-builder-api\target\pipeline-builder-api.jar
popd
exit /b 0
