$env:PYTHONIOENCODING="utf-8"
# Key is read from RAZORPAY_KEY_ID env var — never hardcode credentials here.
if (-not $env:RAZORPAY_KEY_ID) {
    Write-Error "Set RAZORPAY_KEY_ID before running."
    exit 1
}
& "C:\Users\LOQ\AppData\Local\Programs\Python\Python312\python.exe" -u agent_simulator.py --key $env:RAZORPAY_KEY_ID $args
