#!/usr/bin/env python3
"""
agent_simulator.py  —  AgentPay x402-INR end-to-end integration test.

Demonstrates the full autonomous agent payment lifecycle against a live
AgentPay Gateway server using real Razorpay test-mode credentials:

  Phase 1  —  GET /agent/resource  (no proof)  →  HTTP 402 + Razorpay order
  Phase 2  —  Pay via Razorpay Checkout        →  three proof tokens captured
  Phase 3  —  GET /agent/resource  (with proof) →  HTTP 200 + resource data
  Phase 4  —  Replay same proof                →  HTTP 409 Conflict (expected)
  Phase 5  —  10× usage ticks at INR 0.50 each →  consolidated settlement at INR 5.00

Payment automation uses Microsoft Playwright (Chromium) to drive the
Razorpay Checkout modal and the Bank of Baroda Netbanking mock gateway.
No human interaction is needed once the simulator starts.

Usage:
    pip install requests playwright
    playwright install chromium
    python agent_simulator.py --key rzp_test_XXXXXXXXXXXX

Optional flags:
    --server    http://localhost:8080   AgentPay server base URL
    --agent     simulator-agent-001    Agent identifier sent in X-Agent-Id
    --resource  market-data-v1         Resource ID to request
    --skip-meter                       Run phases 1-4 only (skip metering)
"""

import argparse
import json
import sys
import time
import requests

# Windows cmd.exe defaults to cp1252 which cannot print the rupee sign.
# Reconfigure stdout to UTF-8 if the runtime supports it (Python 3.7+).
if hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    except Exception:
        pass

# Module-level globals mutated by main() after argument parsing.
AGENT_ID    = "simulator-agent-001"
RESOURCE_ID = "market-data-v1"
SERVER      = "http://localhost:8080"
KEY_ID      = ""


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def step(label: str):
    """Print a section banner to visually separate simulator phases."""
    print(f"\n{'='*60}")
    print(f"  {label}")
    print(f"{'='*60}")


def check(response: requests.Response, expect_status: int, label: str) -> dict:
    """Assert the HTTP status code and return the parsed JSON body.
    Exits immediately on mismatch so failed assertions are loud and clear.
    """
    if response.status_code != expect_status:
        print(f"[FAIL] {label}")
        print(f"       Expected HTTP {expect_status}, got {response.status_code}")
        print(f"       Body: {response.text[:400]}")
        sys.exit(1)
    print(f"[OK]   {label} -> HTTP {response.status_code}")
    return response.json()


# ---------------------------------------------------------------------------
# Phase 1: Request resource without payment (expect 402)
# ---------------------------------------------------------------------------

def phase_1_request_resource() -> str:
    """
    Sends a bare GET to the gateway with only X-Agent-Id.
    The server returns HTTP 402 with a fresh Razorpay order embedded in
    the response body — this is the x402 discovery step.

    Returns the razorpay_order_id to use in Phase 2's Checkout call.
    """
    step("PHASE 1 -- Request resource (expect 402 Payment Required)")
    url = f"{SERVER}/agent/resource/{RESOURCE_ID}"
    r   = requests.get(url, headers={"X-Agent-Id": AGENT_ID})
    body   = check(r, 402, "GET /agent/resource without proof")
    amount = body.get('amount_paise', 0)
    print(f"       Status   : HTTP 402 Payment Required")
    print(f"       Order ID : {body.get('razorpay_order_id')}")
    print(f"       Amount   : INR {amount / 100:.2f} ({amount} paise)")
    print(f"       x402 hint: {body.get('instructions')}")
    return body["razorpay_order_id"]


# ---------------------------------------------------------------------------
# Phase 2: Pay via Razorpay Checkout (real browser automation)
# ---------------------------------------------------------------------------

def phase_2_pay_via_checkout(order_id: str) -> dict:
    """
    Pays the Razorpay order using a fully automated Chromium browser session.

    Steps:
      1. Spin up a local HTTP server on a random port to serve the Checkout HTML.
      2. Launch Chromium (headed) and navigate to that local page.
      3. Wait for the Razorpay cross-origin iframe to load.
      4. Dismiss any pre-fill overlay (enters contact number if prompted).
      5. Select Netbanking → Bank of Baroda (Razorpay's recommended test bank).
      6. Intercept the Bank of Baroda mock popup and click Success.
      7. Poll window._paymentDone until Razorpay calls the handler callback.

    The three proof tokens (razorpay_payment_id, razorpay_order_id,
    razorpay_signature) are extracted from the Checkout callback and returned.

    Note on port selection: a random OS-assigned port is used (socket.bind
    with port 0) to avoid Windows TIME_WAIT conflicts on back-to-back runs.
    """
    step("PHASE 2 -- Pay via Razorpay Checkout (automated browser)")

    from playwright.sync_api import sync_playwright
    import threading
    import http.server
    import os
    import socket

    # Build the Checkout page.  The handler callback stores the proof dict in
    # window._paymentDone so Python can retrieve it via page.evaluate().
    html_page = f"""<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<script src="https://checkout.razorpay.com/v1/checkout.js"></script>
</head><body style="background:#0f0f1a;margin:0">
<div id="result" style="padding:20px;font-family:monospace;color:#aaa">Waiting for payment...</div>
<script>
window._paymentDone = undefined;
var options = {{
    key:         "{KEY_ID}",
    amount:      "100",
    currency:    "INR",
    name:        "AgentPay Gateway",
    description: "AgentPay x402-INR: unlock {order_id}",
    order_id:    "{order_id}",
    prefill: {{
        name:    "Test Agent",
        email:   "agent@agentpay.dev",
        contact: "+918077907751"
    }},
    handler: function(response) {{
        document.getElementById('result').textContent = JSON.stringify(response);
        window._paymentDone = response;
    }},
    modal: {{
        ondismiss: function() {{
            document.getElementById('result').textContent = 'dismissed';
            window._paymentDone = 'dismissed';
        }}
    }},
    theme: {{ color: "#6366f1" }}
}};
var rzp = new Razorpay(options);
rzp.open();
</script></body></html>"""

    # Bind to port 0 so the OS picks a free port; avoids TIME_WAIT clashes.
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(('127.0.0.1', 0))
        srv_port = s.getsockname()[1]

    class _Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            body = html_page.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type",  "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        def log_message(self, *a):
            pass  # Suppress per-request access logs.

    httpd      = http.server.HTTPServer(("127.0.0.1", srv_port), _Handler)
    srv_thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    srv_thread.start()

    payment_proof = None
    ss_dir        = os.path.dirname(os.path.abspath(__file__))

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=False, slow_mo=80, args=["--no-sandbox"])
        ctx     = browser.new_context(viewport={"width": 1280, "height": 900})
        page    = ctx.new_page()

        page.goto(f"http://127.0.0.1:{srv_port}/checkout", wait_until="domcontentloaded")
        page.wait_for_timeout(4000)

        # Locate the Razorpay cross-origin iframe (url contains "razorpay" + "checkout").
        checkout_frame = None
        for _ in range(30):
            for f in page.frames:
                if "razorpay" in f.url and "checkout" in f.url:
                    checkout_frame = f
                    break
            if checkout_frame:
                break
            page.wait_for_timeout(500)

        if not checkout_frame:
            # Fallback: accept any Razorpay frame (handles canary/beta builds).
            for f in page.frames:
                if "razorpay" in f.url:
                    checkout_frame = f
                    break

        if checkout_frame:
            print("       * Razorpay Checkout modal loaded.")
            try:
                checkout_frame.wait_for_load_state("networkidle", timeout=12000)
            except Exception:
                pass
            page.wait_for_timeout(2000)

            # Dismiss the optional pre-fill "Contact details" overlay by
            # injecting the phone number via React's synthetic event system
            # and clicking Continue.  Safe to call even if overlay is absent.
            try:
                checkout_frame.evaluate("""
                    () => {
                        function nativeSet(el, val) {
                            var setter = Object.getOwnPropertyDescriptor(
                                window.HTMLInputElement.prototype, 'value').set;
                            setter.call(el, val);
                            el.dispatchEvent(new Event('input',  {bubbles: true}));
                            el.dispatchEvent(new Event('change', {bubbles: true}));
                        }
                        var allInputs = document.querySelectorAll('input');
                        for (var inp of allInputs) {
                            var ph = inp.placeholder || '';
                            if (ph.toLowerCase().includes('mobile') || ph.toLowerCase().includes('phone')) {
                                nativeSet(inp, '8077907751');
                                break;
                            }
                        }
                        var btns = document.querySelectorAll('button');
                        for (var b of btns) {
                            if (b.textContent.trim() === 'Continue') {
                                b.click();
                                return 'done';
                            }
                        }
                        return 'no-overlay';
                    }
                """)
                page.wait_for_timeout(1500)
            except Exception:
                pass

            # Select Netbanking.  Bank of Baroda is the recommended test bank
            # because it opens a simple mock page with a single Success button
            # — no OTP, no 3DS, no card details required.
            try:
                checkout_frame.get_by_text("Netbanking").first.click()
                page.wait_for_timeout(1500)
                print("       * Payment method: Netbanking (domestic test rails)")
            except Exception as e:
                print(f"       [WARN] Netbanking selection: {e}")

            try:
                checkout_frame.get_by_text("Bank of Baroda").first.click()
                print("       * Test bank: Bank of Baroda")

                # The mock bank opens in a new popup window (ctx.pages[-1]).
                # Poll for up to 8 seconds, then click Success.
                bank_authorized = False
                for _ in range(16):
                    extra_pages = [pg for pg in ctx.pages if pg != page]
                    if extra_pages:
                        bp = extra_pages[0]
                        try:
                            bp.wait_for_load_state("domcontentloaded", timeout=4000)
                            bp.get_by_role("button", name="Success").click(timeout=4000)
                            print("       * Mock bank gateway: authorized (clicked Success).")
                            bank_authorized = True
                            page.wait_for_timeout(2500)
                            break
                        except Exception:
                            pass
                    page.wait_for_timeout(500)

                if not bank_authorized:
                    print("       * Waiting for payment callback...")

            except Exception as bank_err:
                print(f"       [WARN] Bank authorization: {bank_err}")

        else:
            print("[WARN] Razorpay Checkout frame not found. Payment may fail.")

        # Poll window._paymentDone until the Checkout handler fires.
        deadline = time.time() + 45
        while time.time() < deadline:
            result = page.evaluate("window._paymentDone")
            if result and result != "dismissed":
                payment_proof = result
                pid = result.get("razorpay_payment_id", "")
                print(f"       * Cryptographic proof captured: payment_id={pid}")
                break
            page.wait_for_timeout(1000)

        browser.close()

    httpd.shutdown()

    if not payment_proof:
        print("[FAIL] No payment proof captured within the timeout window.")
        sys.exit(1)

    return payment_proof


# ---------------------------------------------------------------------------
# Phase 3: Retry with proof headers (expect 200)
# ---------------------------------------------------------------------------

def phase_3_retry_with_proof(proof: dict) -> dict:
    """
    Re-sends the same GET with the three proof headers from Phase 2.
    The server verifies the HMAC signature, confirms the payment is captured
    on Razorpay, and checks the audit log for replay — then returns 200 with
    the unlocked resource payload.
    """
    step("PHASE 3 -- Retry resource request with proof headers (expect 200)")
    url = f"{SERVER}/agent/resource/{RESOURCE_ID}"
    headers = {
        "X-Agent-Id":            AGENT_ID,
        "X-Razorpay-Payment-Id": proof["razorpay_payment_id"],
        "X-Razorpay-Order-Id":   proof["razorpay_order_id"],
        "X-Razorpay-Signature":  proof["razorpay_signature"],
    }
    r    = requests.get(url, headers=headers)
    body = check(r, 200, "GET /agent/resource with valid proof")
    print("       Resource unlocked successfully!")
    resource = json.loads(body["resource"])
    for point in resource.get("data_points", []):
        print(f"         * {point}")
    return proof


# ---------------------------------------------------------------------------
# Phase 4: Replay attack (same proof, expect 409 Conflict)
# ---------------------------------------------------------------------------

def phase_4_replay_attack(proof: dict):
    """
    Submits the exact same proof tokens a second time.
    The server detects the duplicate payment_id in the audit log and returns
    409 Conflict.  This validates the replay-protection mechanism works
    correctly — a stolen proof cannot be used to access the resource again.
    """
    step("PHASE 4 -- Replay attack (duplicate proof, expect 409 Conflict)")
    url = f"{SERVER}/agent/resource/{RESOURCE_ID}"
    headers = {
        "X-Agent-Id":            AGENT_ID,
        "X-Razorpay-Payment-Id": proof["razorpay_payment_id"],
        "X-Razorpay-Order-Id":   proof["razorpay_order_id"],
        "X-Razorpay-Signature":  proof["razorpay_signature"],
    }
    r = requests.get(url, headers=headers)
    if r.status_code == 409:
        body = r.json()
        print("[OK]   Replay attack rejected -> HTTP 409 Conflict")
        print(f"       Security enforcement: {body.get('message')}")
    else:
        print(f"[FAIL] Expected 409, got {r.status_code}")
        print(f"       Body: {r.text[:300]}")
        sys.exit(1)


# ---------------------------------------------------------------------------
# Phase 5: Usage metering — micro-accumulation + consolidated settlement
# ---------------------------------------------------------------------------

def phase_5_meter_flow():
    """
    Simulates an AI agent making 10 micro-usage ticks at INR 0.50 each.

    Ticks 1-9 accumulate in the server ledger; no bank transaction is issued.
    Tick 10 crosses the INR 5.00 threshold and the server responds with
    HTTP 402 + a consolidated Razorpay settlement order.  The simulator
    pays that order (reusing Phase 2) and confirms it by re-ticking with
    proof headers.  The server resets the ledger to zero and returns 200.

    This demonstrates AgentPay's solution for INR micro-payments: aggregate
    many sub-₹1 charges into a single bank transaction above the minimum
    viable order size.
    """
    step("PHASE 5 -- Usage metering flow (micro-usage accumulation & settlement)")

    base_url = f"{SERVER}/agent/meter/{RESOURCE_ID}/tick"
    headers  = {
        "X-Agent-Id":   AGENT_ID,
        "Content-Type": "application/json",
    }
    payload = {"tick_paise": 50, "threshold_paise": 500}

    settlement_order_id = None
    ticks_done          = 0

    print("       Sending micro-usage ticks (INR 0.50 each, threshold INR 5.00)...")
    for _ in range(15):
        r    = requests.post(base_url, headers=headers, json=payload)
        body = r.json()
        ticks_done += 1

        if r.status_code == 200:
            acc    = body.get("accumulated_paise", 0)
            thresh = body.get("threshold_paise", 500)
            print(f"       Tick {ticks_done:2d}: INR {acc / 100:.2f} accumulated  (threshold INR {thresh / 100:.2f})")

        elif r.status_code == 402:
            if body.get("status") == "settlement_required":
                settlement_order_id = body.get("razorpay_order_id")
                print(f"       Tick {ticks_done:2d}: THRESHOLD REACHED! Settlement order: {settlement_order_id}")
                break
            elif body.get("status") == "settlement_pending":
                settlement_order_id = body.get("pending_order_id")
                print(f"       Tick {ticks_done:2d}: Settlement pending — order: {settlement_order_id}")
                break

        else:
            print(f"[FAIL] Unexpected status {r.status_code}: {body}")
            sys.exit(1)

    if not settlement_order_id:
        print("[INFO] No settlement order triggered (ledger may have already been reset).")
        return

    print(f"\n       Consolidated settlement: INR 5.00 — order {settlement_order_id}")
    print("       Launching automated Checkout for settlement payment...")

    # Reuse the Phase 2 checkout automation to pay the settlement order.
    proof = phase_2_pay_via_checkout(settlement_order_id)

    # Confirm settlement by re-ticking with proof headers.
    r = requests.post(
        base_url,
        headers={
            "X-Agent-Id":            AGENT_ID,
            "Content-Type":          "application/json",
            "X-Razorpay-Payment-Id": proof["razorpay_payment_id"],
            "X-Razorpay-Order-Id":   proof["razorpay_order_id"],
            "X-Razorpay-Signature":  proof["razorpay_signature"],
        },
        json=payload,
    )
    body = r.json()
    if r.status_code == 200 and body.get("status") == "settled":
        amt = body.get("amount_paise", 0)
        print("[OK]   Meter settlement confirmed. Ledger reset to zero.")
        print(f"       Settled: INR {amt / 100:.2f} ({amt} paise)")
    else:
        print(f"[FAIL] Settlement confirmation failed: {r.status_code} -> {body}")
        sys.exit(1)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    global SERVER, KEY_ID, AGENT_ID, RESOURCE_ID

    parser = argparse.ArgumentParser(
        description="AgentPay x402-INR Gateway — end-to-end integration simulator"
    )
    parser.add_argument("--server",      default="http://localhost:8080",
                        help="AgentPay server base URL (default: http://localhost:8080)")
    parser.add_argument("--key",         required=True,
                        help="Razorpay Key ID (starts with rzp_test_ or rzp_live_)")
    parser.add_argument("--agent",       default="simulator-agent-001",
                        help="Agent identifier sent in the X-Agent-Id header")
    parser.add_argument("--resource",    default="market-data-v1",
                        help="Resource ID to request from the gateway")
    parser.add_argument("--skip-meter",  action="store_true",
                        help="Run phases 1-4 only; skip the metering flow")
    args = parser.parse_args()

    SERVER      = args.server
    KEY_ID      = args.key
    AGENT_ID    = args.agent
    RESOURCE_ID = args.resource

    print("\n  AgentPay Gateway Simulator")
    print(f"  Server  : {SERVER}")
    print(f"  Agent   : {AGENT_ID}")
    print(f"  Resource: {RESOURCE_ID}")

    # Phase 1: Discover the payment requirement.
    order_id = phase_1_request_resource()

    # Phase 2: Autonomously pay via Razorpay Checkout.
    proof = phase_2_pay_via_checkout(order_id)

    # Phase 3: Present proof and receive the unlocked resource.
    phase_3_retry_with_proof(proof)

    # Phase 4: Demonstrate that the same proof cannot be reused.
    phase_4_replay_attack(proof)

    # Phase 5: Run the metering flow (skippable via --skip-meter).
    if not args.skip_meter:
        phase_5_meter_flow()

    print("\n" + "="*60)
    print("  All phases completed successfully.")
    print("  AgentPay Gateway is working end-to-end.")
    print("="*60)


if __name__ == "__main__":
    main()
