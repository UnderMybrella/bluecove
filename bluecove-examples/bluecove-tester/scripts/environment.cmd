@echo off
rem @version $Revision$ ($Author$)  $Date$

call %~dp0version.cmd

set DEFAULT_BUILD_HOME=%~dp0
for /f %%i in ("%DEFAULT_BUILD_HOME%..") do @set DEFAULT_BUILD_HOME=%%~fi

set WMDPT="%ProgramFiles%\Windows Mobile Developer Power Toys"
if exist %WMDPT%\CECopy\cecopy.exe goto pt_found
echo Windows Mobile Developer Power Toys Not Found
goto :errormark

:pt_found

set XWIN_CE_PHONE=true

if NOT '%WIN_CE_PHONE%' EQU 'true' (
    set BLUECOVE_INSTALL_DIR=\bluecove
)

if '%WIN_CE_PHONE%' EQU 'true' (
    set BLUECOVE_INSTALL_DIR=\Storage\bluecove
)

rem set BLUECOVE_INSTALL_DIR=\Storage Card\bluecove


@for /f "tokens=*" %%I in ('CD') do @set CurDir=%%~nI
@title %CurDir%
goto endmark
:errormark
	exit /b 1
:endmark

