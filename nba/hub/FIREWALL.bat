@echo off
setlocal EnableExtensions
title nba hub - open the firewall
cd /d "%~dp0"

REM ------------------------------------------------------------------
REM  The phone talks to this PC on two port ranges:
REM    8790        the hub page + API
REM    8900-8959   the video relays (one per stream)
REM  Windows blocks both by default on a Private/Domain network.
REM  Run this once, as admin. It only opens those TCP ports inbound.
REM ------------------------------------------------------------------

net session >nul 2>&1
if errorlevel 1 (
    echo asking for administrator rights...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b 0
)

echo.
echo [1/2] hub port 8790
netsh advfirewall firewall delete rule name="NBA Hub 8790" >nul 2>&1
netsh advfirewall firewall add rule name="NBA Hub 8790" dir=in action=allow protocol=TCP localport=8790 profile=any

echo [2/2] relay ports 8900-8959
netsh advfirewall firewall delete rule name="NBA Hub relays 8900-8959" >nul 2>&1
netsh advfirewall firewall add rule name="NBA Hub relays 8900-8959" dir=in action=allow protocol=TCP localport=8900-8959 profile=any

echo.
echo current rules:
netsh advfirewall firewall show rule name="NBA Hub 8790" | findstr /i "LocalPort Action Enabled"
netsh advfirewall firewall show rule name="NBA Hub relays 8900-8959" | findstr /i "LocalPort Action Enabled"

echo.
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4"') do (
    for /f "tokens=* delims= " %%b in ("%%a") do echo phone url:  http://%%b:8790/
)
echo.
echo done. if the phone still cannot connect, check that both devices are
echo on the same wifi and that the network is not "guest / isolated".
echo.
pause
