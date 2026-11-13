@echo off
setlocal
pushd "%~dp0..\.." || exit /b 1
set "RESPONSE_JAVA=java"
if defined JAVA_HOME set "RESPONSE_JAVA=%JAVA_HOME%\bin\java.exe"
"%RESPONSE_JAVA%" -cp "plugins\universal-response\target\universal-response-plugin.jar;pipeline-api\target\pipeline-api-1.0.0.jar" com.core.docintel.response.UniversalResponseDemo %*
set "RESPONSE_EXIT=%ERRORLEVEL%"
popd
exit /b %RESPONSE_EXIT%
