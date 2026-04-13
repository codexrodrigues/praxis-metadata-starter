@echo off
setlocal

set "BASEDIR=%~dp0"
if "%BASEDIR:~-1%"=="\" set "BASEDIR=%BASEDIR:~0,-1%"

if "%JAVA_HOME%"=="" (
  set "JAVACMD=java.exe"
) else (
  set "JAVACMD=%JAVA_HOME%\bin\java.exe"
)

if not exist "%BASEDIR%\.mvn\wrapper\maven-wrapper.jar" (
  echo Error: Maven Wrapper jar is missing: %BASEDIR%\.mvn\wrapper\maven-wrapper.jar
  exit /B 1
)

"%JAVACMD%" %MAVEN_OPTS% ^
  -classpath "%BASEDIR%\.mvn\wrapper\maven-wrapper.jar" ^
  "-Dmaven.multiModuleProjectDirectory=%BASEDIR%" ^
  org.apache.maven.wrapper.MavenWrapperMain %*

exit /B %ERRORLEVEL%
