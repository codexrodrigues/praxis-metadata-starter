@echo off

set MAVEN_CMD=mvn

where %MAVEN_CMD% >NUL 2>&1

if %ERRORLEVEL%==0 (

  %MAVEN_CMD% %*

) else (

  echo Error: Maven is not installed and Maven Wrapper jar is missing.

  exit /B 1

)

