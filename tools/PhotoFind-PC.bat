@echo off
if not exist "%~dp0.venv\Scripts\pythonw.exe" (
  echo PhotoFind is not installed on this PC yet.
  echo Run "Install PhotoFind.bat" in the project folder first.
  pause
  exit /b 1
)
start "" "%~dp0.venv\Scripts\pythonw.exe" "%~dp0pc_gui.py"
