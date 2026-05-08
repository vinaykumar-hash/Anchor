@echo off
set "CUDA_EXT=C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v12.4\extras\visual_studio_integration\MSBuildExtensions"
set "VS_EXT=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\MSBuild\Microsoft\VC\v170\BuildCustomizations"

echo Copying from %CUDA_EXT% to %VS_EXT%
copy /Y "%CUDA_EXT%\*" "%VS_EXT%\"
if errorlevel 1 (
    echo Failed to copy.
) else (
    echo Successfully copied files!
)
