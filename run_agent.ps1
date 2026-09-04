$env:PYTHONIOENCODING="utf-8"
if (-not $env:RAZORPAY_KEY_ID) {
    Write-Error "Set RAZORPAY_KEY_ID before running. Example: `$env:RAZORPAY_KEY_ID='rzp_test_xxx'"
    exit 1
}
$pyExe = if (Get-Command python -ErrorAction SilentlyContinue) {
    "python"
} elseif (Get-Command py -ErrorAction SilentlyContinue) {
    "py"
} elseif (Test-Path "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe") {
    "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe"
} else {
    "python"
}
& $pyExe -u ai_agent.py --key $env:RAZORPAY_KEY_ID $args
