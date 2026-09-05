#!/usr/bin/env python3
"""
agent_simulator.py  --  AgentPay x402-inspired integration test.

Simulates an autonomous AI agent going through the full payment lifecycle:

  Phase 1  GET /agent/resource  (no proof)   -> 402 + Razorpay order
  Phase 2  Pay via Razorpay Checkout         -> three proof tokens
  Phase 3  GET /agent/resource  (with proof) -> 200 + resource data
  Phase 4  Replay the same proof             -> 409 Conflict (expected)
  Phase 5  10x usage ticks -> threshold -> consolidated settlement

Usage:
    pip install requests playwright
    playwright install chromium
    python agent_simulator.py --key YOUR_RAZORPAY_KEY_ID
"""

import argparse
import json
import sys
import time
import requests

if hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    except Exception:
        pass

AGENT_ID    = "simulator-agent-001"
RESOURCE_ID = "market-data-v1"
SERVER      = "http://localhost:8080"
KEY_ID      = ""


def step(label):
    print(f"\n{'='*60}\n  {label}\n{'='*60}")


def check(response, expect_status, label):
    if response.status_code != expect_status:
        print(f"[FAIL] {label}")
        print(f"       Expected {expect_status}, got {response.status_code}")
        print(f"       Body: {response.text[:400]}")
        sys.exit(1)
    print(f"[OK]   {label} -> HTTP {response.status_code}")
    return response.json()


def phase_1_request_resource():
    step("PHASE 1 -- Request resource (expect 402 Payment Required)")
    r    = requests.get(f"{SERVER}/agent/resource/{RESOURCE_ID}",
                        headers={"X-Agent-Id": AGENT_ID})
    body = check(r, 402, "GET /agent/resource without proof")
    amt  = body.get("amount_paise", 0)
    print(f"       Status   : HTTP 402 Payment Required")
    print(f"       Order ID : {body.get('razorpay_order_id')}")
    print(f"       Amount   : INR {amt / 100:.2f} ({amt} paise)")
    print(f"       x402 hint: {body.get('instructions')}")
    return body["razorpay_order_id"], amt


def phase_2_pay_via_checkout(order_id, amount_paise):
    """
    Open a real Razorpay Checkout in headless Chromium and complete payment
    via the Bank of Baroda test netbanking flow.

    amount_paise is passed through so the Checkout HTML uses the actual order
    amount rather than a hardcoded constant — fixing the settlement-order bug
    where Phase 5 called this with 500 paise but the HTML always sent 100.
    """
    step("PHASE 2 -- Pay via Razorpay Checkout (automated browser)")

    from playwright.sync_api import sync_playwright
    import threading, http.server, socket

    html = f"""<!DOCTYPE html>
<html><head><meta charset="utf-8">
<script src="https://checkout.razorpay.com/v1/checkout.js"></script>
</head><body style="background:#0f0f1a;margin:0">
<div id="r" style="padding:20px;font-family:monospace;color:#aaa">Waiting...</div>
<script>
window._done = undefined;
var rzp = new Razorpay({{
    key:         "{KEY_ID}",
    amount:      "{amount_paise}",
    currency:    "INR",
    name:        "AgentPay Gateway",
    description: "Unlock {order_id}",
    order_id:    "{order_id}",
    prefill: {{ name:"Test Agent", email:"agent@agentpay.dev", contact:"8077907751" }},
    handler:     function(r){{ document.getElementById('r').textContent=JSON.stringify(r); window._done=r; }},
    modal:       {{ ondismiss: function(){{ window._done='dismissed'; }} }},
    theme:       {{ color:"#6366f1" }}
}});
rzp.open();
</script></body></html>"""

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]

    class _H(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            b = html.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type",  "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)
        def log_message(self, *a): pass

    httpd = http.server.HTTPServer(("127.0.0.1", port), _H)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()

    proof = None
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=False, slow_mo=80, args=["--no-sandbox"])
        ctx  = browser.new_context(viewport={"width": 1280, "height": 900})
        page = ctx.new_page()

        page.goto(f"http://127.0.0.1:{port}/checkout", wait_until="domcontentloaded")
        page.wait_for_timeout(4000)

        frame = None
        for _ in range(30):
            for f in page.frames:
                if "razorpay" in f.url and "checkout" in f.url:
                    frame = f; break
            if frame: break
            page.wait_for_timeout(500)
        if not frame:
            for f in page.frames:
                if "razorpay" in f.url:
                    frame = f; break

        if frame:
            print("       * Razorpay Checkout modal loaded.")
            try: frame.wait_for_load_state("networkidle", timeout=12000)
            except Exception: pass
            page.wait_for_timeout(2000)

            # Dismiss phone-number pre-fill overlay if shown.
            try:
                frame.evaluate("""() => {
                    function set(el, v) {
                        Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')
                            .set.call(el,v);
                        el.dispatchEvent(new Event('input',{bubbles:true}));
                        el.dispatchEvent(new Event('change',{bubbles:true}));
                    }
                    for (var i of document.querySelectorAll('input')) {
                        var p = i.placeholder||'';
                        if(p.toLowerCase().includes('mobile')||p.toLowerCase().includes('phone')){
                            set(i,'8077907751'); break;
                        }
                    }
                    var btns = Array.from(document.querySelectorAll('button')).filter(b => b.textContent.trim() === 'Continue');
                    if (btns.length > 0) btns[btns.length - 1].click();
                }""")
                page.wait_for_timeout(1500)
            except Exception: pass

            # Remove any modal/backdrop overlays intercepting clicks
            try:
                frame.evaluate("""() => {
                    var overlays = document.querySelectorAll('.stack-overlay, #overlay-backdrop, [data-testid*="overlay"]');
                    for (var o of overlays) o.remove();
                }""")
            except Exception: pass

            try:
                frame.get_by_text("Netbanking").first.click(force=True)
                page.wait_for_timeout(1500)
                print("       * Payment method: Netbanking (domestic test rails)")
            except Exception as e:
                print(f"       [WARN] Netbanking: {e}")

            try:
                bob = frame.get_by_text("Bank of Baroda").first
                bob.click(force=True)
                print("       * Test bank: Bank of Baroda")
                authorized = False
                for _ in range(25):
                    extras = [pg for pg in ctx.pages if pg != page]
                    if extras:
                        bp = extras[0]
                        try:
                            if "about:blank" in bp.url:
                                page.wait_for_timeout(500)
                                continue
                            bp.wait_for_load_state("domcontentloaded", timeout=5000)
                            s_btn = bp.locator('button:has-text("Success"), button[value="Success"], input[value="Success"]').first
                            s_btn.wait_for(state="visible", timeout=5000)
                            s_btn.click()
                            print("       * Test bank: payment authorized.")
                            authorized = True
                            page.wait_for_timeout(2500)
                            break
                        except Exception: pass
                    page.wait_for_timeout(500)
                if not authorized:
                    print("       * Waiting for payment callback...")
            except Exception as e:
                print(f"       [WARN] Bank auth: {e}")
        else:
            print("[WARN] Checkout frame not found.")

        deadline = time.time() + 45
        while time.time() < deadline:
            result = page.evaluate("window._done")
            if result and result != "dismissed":
                proof = result
                print(f"       * Cryptographic proof captured: payment_id={result.get('razorpay_payment_id','')}")
                break
            page.wait_for_timeout(1000)

        browser.close()

    httpd.shutdown()

    if not proof:
        print("[FAIL] No payment proof captured.")
        sys.exit(1)

    return proof


def phase_3_retry_with_proof(proof):
    step("PHASE 3 -- Retry resource request with proof headers (expect 200)")
    r = requests.get(f"{SERVER}/agent/resource/{RESOURCE_ID}", headers={
        "X-Agent-Id":            AGENT_ID,
        "X-Razorpay-Payment-Id": proof["razorpay_payment_id"],
        "X-Razorpay-Order-Id":   proof["razorpay_order_id"],
        "X-Razorpay-Signature":  proof["razorpay_signature"],
    })
    body = check(r, 200, "GET /agent/resource with valid proof")
    print("       Resource unlocked successfully!")
    for pt in json.loads(body["resource"]).get("data_points", []):
        print(f"         * {pt}")
    return proof


def phase_4_replay_attack(proof):
    step("PHASE 4 -- Replay attack (duplicate proof, expect 409 Conflict)")
    r = requests.get(f"{SERVER}/agent/resource/{RESOURCE_ID}", headers={
        "X-Agent-Id":            AGENT_ID,
        "X-Razorpay-Payment-Id": proof["razorpay_payment_id"],
        "X-Razorpay-Order-Id":   proof["razorpay_order_id"],
        "X-Razorpay-Signature":  proof["razorpay_signature"],
    })
    if r.status_code == 409:
        print("[OK]   Replay attack rejected -> HTTP 409 Conflict")
        print(f"       Security enforcement: {r.json().get('message')}")
    else:
        print(f"[FAIL] Expected 409, got {r.status_code}: {r.text[:300]}")
        sys.exit(1)


def phase_5_meter_flow():
    step("PHASE 5 -- Usage metering flow (micro-usage accumulation & settlement)")

    url     = f"{SERVER}/agent/meter/{RESOURCE_ID}/tick"
    headers = {"X-Agent-Id": AGENT_ID, "Content-Type": "application/json"}
    payload = {"tick_paise": 50, "threshold_paise": 500}

    settlement_order  = None
    settlement_amount = 0
    print("       Sending micro-usage ticks (INR 0.50 each, threshold INR 5.00)...")

    for i in range(15):
        r    = requests.post(url, headers=headers, json=payload)
        body = r.json()

        if r.status_code == 200:
            acc = body.get("accumulated_paise", 0)
            thr = body.get("threshold_paise", 500)
            print(f"       Tick {i+1:2d}: INR {acc/100:.2f} accumulated  (threshold INR {thr/100:.2f})")
        elif r.status_code == 402:
            if body.get("status") == "settlement_required":
                settlement_order  = body["razorpay_order_id"]
                settlement_amount = body.get("accumulated_paise", 500)
                print(f"       Tick {i+1:2d}: THRESHOLD REACHED! Settlement order: {settlement_order}")
                break
            elif body.get("status") == "settlement_pending":
                settlement_order  = body["pending_order_id"]
                settlement_amount = body.get("accumulated_paise", 500)
                print(f"       Tick {i+1:2d}: Pending order: {settlement_order}")
                break
        else:
            print(f"[FAIL] Tick {i+1}: {r.status_code} {body}"); sys.exit(1)

    if not settlement_order:
        print("[INFO] No settlement triggered this run."); return

    print(f"\n       Consolidated settlement: INR {settlement_amount/100:.2f} — order {settlement_order}")
    print("       Launching automated Checkout for settlement payment...")

    # Pass the actual accumulated amount so Checkout sends the correct figure.
    proof = phase_2_pay_via_checkout(settlement_order, settlement_amount)

    r = requests.post(url, headers={
        **headers,
        "X-Razorpay-Payment-Id": proof["razorpay_payment_id"],
        "X-Razorpay-Order-Id":   proof["razorpay_order_id"],
        "X-Razorpay-Signature":  proof["razorpay_signature"],
    }, json=payload)
    body = r.json()
    if r.status_code == 200 and body.get("status") == "settled":
        amt = body.get("amount_paise", 0)
        print(f"[OK]   Meter settlement confirmed. Ledger reset to zero.")
        print(f"       Settled: INR {amt/100:.2f} ({amt} paise)")
    else:
        print(f"[FAIL] Settlement: {r.status_code} {body}"); sys.exit(1)


def main():
    global SERVER, KEY_ID, AGENT_ID, RESOURCE_ID

    p = argparse.ArgumentParser(description="AgentPay x402-inspired simulator")
    p.add_argument("--server",     default="http://localhost:8080")
    p.add_argument("--key",        required=True, help="Razorpay Key ID")
    p.add_argument("--agent",      default="simulator-agent-001")
    p.add_argument("--resource",   default="market-data-v1")
    p.add_argument("--skip-meter", action="store_true")
    args = p.parse_args()

    SERVER      = args.server
    KEY_ID      = args.key
    AGENT_ID    = args.agent
    RESOURCE_ID = args.resource

    print(f"\n  AgentPay Gateway Simulator")
    print(f"  Server  : {SERVER}")
    print(f"  Agent   : {AGENT_ID}")
    print(f"  Resource: {RESOURCE_ID}")

    order_id, amount_paise = phase_1_request_resource()
    proof = phase_2_pay_via_checkout(order_id, amount_paise)
    phase_3_retry_with_proof(proof)
    phase_4_replay_attack(proof)
    if not args.skip_meter:
        phase_5_meter_flow()

    print("\n" + "="*60)
    print("  All phases completed successfully.")
    print("  AgentPay Gateway is working end-to-end.")
    print("="*60)


if __name__ == "__main__":
    main()
