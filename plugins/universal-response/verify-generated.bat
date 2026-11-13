@echo off
setlocal
pushd "%~dp0..\.." || exit /b 1
set "RESPONSE_JAVA=java"
set "RESPONSE_JAVAC=javac"
if defined JAVA_HOME set "RESPONSE_JAVA=%JAVA_HOME%\bin\java.exe"
if defined JAVA_HOME set "RESPONSE_JAVAC=%JAVA_HOME%\bin\javac.exe"
"%RESPONSE_JAVAC%" -proc:none -classpath "pipeline-builder-api\target\pipeline-builder-api.jar" -d "plugins\universal-response\target\integration-tools" "plugins\universal-response\src\test\integration\GeneratedApplicationSmoke.java"
if errorlevel 1 goto failed
"%RESPONSE_JAVA%" -classpath "plugins\universal-response\target\integration-tools;pipeline-builder-api\target\pipeline-builder-api.jar" com.core.docintel.pipeline.builder.GeneratedApplicationSmoke
if errorlevel 1 goto failed
popd
exit /b 0
:failed
popd
exit /b 1
