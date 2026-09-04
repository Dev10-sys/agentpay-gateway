@echo off
set PYTHONIOENCODING=utf-8
if "%RAZORPAY_KEY_ID%"=="" (
    echo Error: Set RAZORPAY_KEY_ID before running.
    echo Example: set RAZORPAY_KEY_ID=rzp_test_xxx
    exit /b 1
)
python -u agent_simulator.py --key %RAZORPAY_KEY_ID% %*
