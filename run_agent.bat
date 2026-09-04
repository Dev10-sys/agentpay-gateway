@echo off
set PYTHONIOENCODING=utf-8
if "%RAZORPAY_KEY_ID%"=="" (
    echo Error: Set RAZORPAY_KEY_ID before running.
    echo Example: set RAZORPAY_KEY_ID=rzp_test_xxx
    exit /b 1
)
set PY_EXE=python
if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" (
    set PY_EXE="%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
)
%PY_EXE% -u ai_agent.py --key %RAZORPAY_KEY_ID% %*
