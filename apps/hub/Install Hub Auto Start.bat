@echo off
setlocal
set "APPDIR=%LOCALAPPDATA%\OrderRecorderHub\App"
set "TARGET=%APPDIR%\OrderRecorderHub.exe"
echo Installing Order Recorder Hub Auto Start...
if not exist "%APPDIR%" mkdir "%APPDIR%"
taskkill /IM OrderRecorderHub.exe /F >nul 2>&1
copy /Y "%~dp0OrderRecorderHub.exe" "%TARGET%" >nul
if errorlevel 1 (
  echo Khong copy duoc OrderRecorderHub.exe.
  pause
  exit /b 1
)
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "OrderRecorderHub" /t REG_SZ /d "\"%TARGET%\" --background" /f >nul
start "" "%TARGET%" --background
timeout /t 2 /nobreak >nul
start "" "http://127.0.0.1:17891"
echo.
echo Da cai dat. Tu lan dang nhap Windows tiep theo Hub se tu chay nen.
pause
