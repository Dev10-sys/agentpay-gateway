@echo off
set PYTHONIOENCODING=utf-8
if "%RAZORPAY_KEY_ID%"=="" (
    echo Error: Set RAZORPAY_KEY_ID before running.
    echo Example: set RAZORPAY_KEY_ID=rzp_test_xxx
    exit /b 1
)
set PY_EXE=python
where python >nul 2>nul
if %ERRORLEVEL% neq 0 (
    where py >nul 2>nul
    if %ERRORLEVEL% equ 0 (
        set PY_EXE=py
    ) else if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" (
        set PY_EXE="%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
    )
)
%PY_EXE% -u agent_simulator.py --key %RAZORPAY_KEY_ID% %*
