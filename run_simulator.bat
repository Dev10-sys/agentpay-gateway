@echo off
set PYTHONIOENCODING=utf-8
if "%RAZORPAY_KEY_ID%"=="" (
    echo Error: Set RAZORPAY_KEY_ID before running.
    exit /b 1
)
"C:\Users\LOQ\AppData\Local\Programs\Python\Python312\python.exe" -u agent_simulator.py --key %RAZORPAY_KEY_ID% %*
