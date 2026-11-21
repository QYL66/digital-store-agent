@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Start-StoreAgent.ps1"
if errorlevel 1 pause
