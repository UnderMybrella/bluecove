@echo off
rem @version $Revision$ ($Author$)  $Date$
SETLOCAL
call %~dp0scripts\version.cmd
java -Dbluecove.stack=bluesoleil -jar target\bluecove-tester-%VERSION%-app.jar
if errorlevel 1 (
    echo Error calling java
    pause
)

ENDLOCAL