@echo off
set "DIR=%~dp0\.."
java -jar "%DIR%\target\ffdec-mcp-1.0.0.jar" %*
