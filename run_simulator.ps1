$env:PYTHONIOENCODING="utf-8"
if (-not $env:RAZORPAY_KEY_ID) {
    $env:RAZORPAY_KEY_ID = "rzp_test_TX90BbXSlKMqWP"
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
& $pyExe -u agent_simulator.py --key $env:RAZORPAY_KEY_ID $args
