@echo off
net session >nul 2>&1
if %errorlevel% neq 0 (
  echo Vui long chuot phai file nay va chon "Run as administrator".
  pause
  exit /b 1
)
netsh advfirewall firewall delete rule name="Order Recorder Hub" >nul 2>&1
netsh advfirewall firewall add rule name="Order Recorder Hub" dir=in action=allow protocol=TCP localport=17891
if %errorlevel% equ 0 (
  echo Da mo cong 17891 cho Order Recorder Hub.
) else (
  echo Khong mo duoc Windows Firewall.
)
pause
