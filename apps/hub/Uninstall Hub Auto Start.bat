@echo off
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "OrderRecorderHub" /f >nul 2>&1
taskkill /IM OrderRecorderHub.exe /F >nul 2>&1
echo Da tat Auto Start cua Order Recorder Hub.
pause
