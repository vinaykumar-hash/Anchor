@echo off
reg add "HKLM\SOFTWARE\NVIDIA Corporation\GPU Computing Toolkit\CUDA\v12.4" /v "InstallDir" /t REG_SZ /d "C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v12.4\\" /f
if errorlevel 1 (
    echo Failed to add registry key.
    pause
) else (
    echo Successfully added registry key!
)
