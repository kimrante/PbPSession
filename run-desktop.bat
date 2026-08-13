@echo off
REM Launch the PbP desktop app. Double-click, or run from a terminal.
REM ASCII only on purpose: cmd.exe reads .bat in the OEM codepage (949 here),
REM so UTF-8 Korean in this file breaks command parsing, not just the display.
cd /d "%~dp0"

echo Starting PbP desktop. The first run builds, so it takes a while.
echo Closing the app window ends this console too.
echo.

REM Full path, not a bare name: some setups (NoDefaultCurrentDirectoryInExePath)
REM do not search the current folder, and a bare gradlew.bat is then "not recognized".
REM Extra args pass through, e.g. run-desktop.bat --offline
call "%~dp0gradlew.bat" :desktop:run %*

REM Hold the window open only on failure, so a double-click shows the error.
if errorlevel 1 (
    echo.
    echo Failed to start. See the messages above.
    pause
)
