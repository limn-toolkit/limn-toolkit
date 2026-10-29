@echo off
rem build.cmd: the demo as one native executable on Windows x64, as build.sh does elsewhere.
rem Needs the Visual Studio Build Tools with the x64 compiler and a Windows SDK; this sets up their
rem environment itself. GRAALVM_HOME and DEMO_JAR as in build.sh; extra arguments go to native-image.
rem The executable is linked for the Windows subsystem, as a window program, because a console
rem program opened from Explorer brings a console window up beside its own; its log then goes
rem nowhere, and a console build for reading it is one CONSOLE=1 away.
setlocal
if "%GRAALVM_HOME%"=="" (echo set GRAALVM_HOME & exit /b 2)
if "%DEMO_JAR%"=="" (echo set DEMO_JAR & exit /b 2)
for /f "usebackq tokens=*" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -property installationPath`) do set VS=%%i
call "%VS%\VC\Auxiliary\Build\vcvarsall.bat" x64 >nul || exit /b 1
set META=%~dp0metadata\windows-x86_64
set SUBSYSTEM=-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS -H:NativeLinkerOption=/ENTRY:mainCRTStartup
if "%CONSOLE%"=="1" set SUBSYSTEM=
if not exist "%~dp0..\..\build\native-image" mkdir "%~dp0..\..\build\native-image"
"%GRAALVM_HOME%\bin\native-image.cmd" -J-Xmx5g --parallelism=4 --enable-native-access=ALL-UNNAMED ^
  -H:+IncludeAllLocales -H:ConfigurationFileDirectories=%META%,%META%\resources %SUBSYSTEM% ^
  %* -jar "%DEMO_JAR%" -o "%~dp0..\..\build\native-image\limn-demo"
