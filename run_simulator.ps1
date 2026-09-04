$env:PYTHONIOENCODING="utf-8"
if (-not $env:RAZORPAY_KEY_ID) {
    Write-Error "Set RAZORPAY_KEY_ID before running. Example: `$env:RAZORPAY_KEY_ID='rzp_test_xxx'"
    exit 1
}
python -u agent_simulator.py --key $env:RAZORPAY_KEY_ID $args
