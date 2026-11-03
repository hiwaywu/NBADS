@echo off
title NBA - game list
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0games.ps1"
