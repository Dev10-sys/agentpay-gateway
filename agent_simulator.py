#!/usr/bin/env python3
"""
agent_simulator.py -- AgentPay x402-INR Gateway simulator.

This script acts as an autonomous AI agent that:
  1. Requests a protected resource (GET /agent/resource/{id}) -- gets HTTP 402.
  2. Launches a headless Playwright browser to pay via Razorpay Checkout.
  3. Captures the three proof tokens returned by Checkout.
  4. Retries the same GET with proof headers -- gets HTTP 200 + unlocked resource.
  5. Deliberately replays the same proof to demonstrate graceful rejection.
  6. Runs the metering flow: ticks until threshold, settles, continues.

Usage:
    pip install requests playwright
    playwright install chromium
    python agent_simulator.py --key YOUR_KEY_ID --server http://localhost:8080

Razorpay test UPI: success@razorpay  (instant captured status, no OTP)
Razorpay test card: 4111 1111 1111 1111, any future expiry, any CVV, OTP: 1234
"""

import argparse
import json
import sys
import time
import requests

AGENT_ID   = "simulator-agent-001"
RESOURCE_ID = "market-data-v1"
SERVER      = "http://localhost:8080"
KEY_ID      = ""


def step(label: str):
    print(f"\n{'='*60}")
    print(f"  {label}")
    print(f"{'='*60}")


def check(response: requests.Response, expect_status: int, label: str):
    if response.status_code != expect_status:
        print(f"[FAIL] {label}")
        print(f"       Expected {expect_status}, got {response.status_code}")
        print(f"       Body: {response.text[:400]}")
        sys.exit(1)
    print(f"[OK]   {label} -> HTTP {response.status_code}")
    return response.json()


def phase_1_request_resource():
    step("PHASE 1 -- Request resource (expect 402 Payment Required)")
    url = f"{SERVER}/agent/resource/{RESOURCE_ID}"
    r = requests.get(url, headers={"X-Agent-Id": AGENT_ID})
    body = check(r, 402, "GET /agent/resource without proof")
    print(f"       Order ID : {body.get('razorpay_order_id')}")
    print(f"       Amount   : {body.get('amount_paise')} paise")
    print(f"       x402 hint: {body.get('instructions')}")
    return body["razorpay_order_id"]


def phase_2_pay_via_checkout(order_id: str):
    step("PHASE 2 -- Pay via Razorpay Checkout (headless browser)")

    from playwright.sync_api import sync_playwright

    html_page = f"""<!DOCTYPE html><html><head>
    <script src="https://checkout.razorpay.com/v1/checkout.js"></script>
    </head><body>
    <div id="result"></div>
    <script>
    var options = {{
        key: "{KEY_ID}",
        amount: "100",
        currency: "INR",
        name: "AgentPay Gateway",
        description: "AgentPay x402-INR resource unlock",
        order_id: "{order_id}",
        prefill: {{
            name: "Test Agent",
            email: "agent@agentpay.dev",
            contact: "+919999999999"
        }},
        handler: function(response) {{
            document.getElementById('result').textContent = JSON.stringify(response);
            window._paymentDone = response;
        }},
        theme: {{ color: "#6366f1" }}
    }};
    var rzp = new Razorpay(options);
    rzp.open();
    </script></body></html>"""

    payment_proof = None

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=False, slow_mo=300)
        page    = browser.new_page()

        page.set_content(html_page)
        page.wait_for_timeout(3000)

        print("       Waiting for Razorpay Checkout iframe to load...")
        try:
            page.wait_for_selector("iframe[src*='checkout.razorpay']", timeout=15000)
        except Exception:
            print("[WARN] Checkout iframe not detected; trying UPI fallback path...")

        try:
            frame = page.frame_locator("iframe[src*='checkout.razorpay']").first

            # Try UPI flow first (fastest, no OTP)
            try:
                frame.get_by_text("UPI", exact=False).first.click(timeout=5000)
                page.wait_for_timeout(1000)
                upi_input = frame.locator("input[placeholder*='UPI'], input[name*='upi']").first
                upi_input.fill("success@razorpay")
                frame.get_by_text("Pay", exact=False).first.click(timeout=5000)
                page.wait_for_timeout(5000)
                print("       UPI payment submitted via success@razorpay")
            except Exception:
                # Fallback: test card
                print("       UPI path not found; using test card 4111 1111 1111 1111")
                try:
                    frame.get_by_text("Card", exact=False).first.click(timeout=5000)
                    page.wait_for_timeout(800)
                except Exception:
                    pass

                card_input = frame.locator("input[name='card[number]'], input[placeholder*='Card']").first
                card_input.fill("4111 1111 1111 1111")
                frame.locator("input[name='card[expiry]']").fill("12/26")
                frame.locator("input[name='card[cvv]']").fill("123")
                frame.get_by_text("Pay").first.click()
                page.wait_for_timeout(3000)
                # Handle OTP
                try:
                    otp_input = page.locator("input[name='otp'], input[placeholder*='OTP']").first
                    otp_input.fill("1234")
                    page.locator("button[type='submit']").click()
                    page.wait_for_timeout(4000)
                except Exception:
                    pass

        except Exception as e:
            print(f"[WARN] Checkout interaction issue: {e}")

        print("       Waiting for payment result from Checkout handler...")
        deadline = time.time() + 30
        while time.time() < deadline:
            result = page.evaluate("window._paymentDone")
            if result:
                payment_proof = result
                print(f"       Proof captured: payment_id={result.get('razorpay_payment_id', '')[:20]}...")
                break
            page.wait_for_timeout(1000)

        browser.close()

    if not payment_proof:
        print("[FAIL] Did not capture payment proof within 30 seconds.")
        print("       If running headless, you may need to set headless=False manually above.")
        sys.exit(1)

    return payment_proof


def phase_3_retry_with_proof(proof: dict):
    step("PHASE 3 -- Retry resource request with proof headers (expect 200)")
    url = f"{SERVER}/agent/resource/{RESOURCE_ID}"
    headers = {
        "X-Agent-Id":            AGENT_ID,
        "X-Razorpay-Payment-Id": proof["razorpay_payment_id"],
        "X-Razorpay-Order-Id":   proof["razorpay_order_id"],
        "X-Razorpay-Signature":  proof["razorpay_signature"],
    }
    r = requests.get(url, headers=headers)
    body = check(r, 200, "GET /agent/resource with valid proof")
    print(f"       Resource unlocked!")
    resource = json.loads(body["resource"])
    for point in resource.get("data_points", []):
        print(f"         * {point}")
    return proof


def phase_4_replay_attack(proof: dict):
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
        print(f"[OK]   Replay correctly rejected -> HTTP 409")
        print(f"       Server says: {body.get('message')}")
    else:
        print(f"[FAIL] Expected 409 for replay attack, got {r.status_code}")
        print(f"       Body: {r.text[:300]}")
        sys.exit(1)


def phase_5_meter_flow():
    step("PHASE 5 -- Usage metering flow (tick -> accumulate -> threshold -> settle)")

    base_url = f"{SERVER}/agent/meter/{RESOURCE_ID}/tick"
    headers  = {
        "X-Agent-Id":    AGENT_ID,
        "Content-Type":  "application/json",
    }
    payload = {"tick_paise": 10, "threshold_paise": 50}

    settlement_order_id = None
    ticks_done = 0

    print("       Ticking (10 paise each, threshold 50 paise)...")
    for i in range(10):
        r = requests.post(base_url, headers=headers, json=payload)
        body = r.json()
        ticks_done += 1

        if r.status_code == 200:
            print(f"       Tick {ticks_done}: {body.get('accumulated_paise')} / {body.get('threshold_paise')} paise")
        elif r.status_code == 402:
            if body.get("status") == "settlement_required":
                settlement_order_id = body.get("razorpay_order_id")
                print(f"       Tick {ticks_done}: THRESHOLD CROSSED! Settlement order: {settlement_order_id}")
                break
            elif body.get("status") == "settlement_pending":
                print(f"       Tick {ticks_done}: Settlement still pending -> {body.get('pending_order_id')}")
                break
        else:
            print(f"[FAIL] Unexpected status {r.status_code}: {body}")
            sys.exit(1)

    if not settlement_order_id:
        print("[INFO] No settlement order triggered in this run (may have already settled).")
        return

    print(f"\n       Settlement required for order: {settlement_order_id}")
    print("       Launching Checkout to settle metered usage...")

    proof = phase_2_pay_via_checkout(settlement_order_id)

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
        print(f"[OK]   Meter settlement confirmed. Ledger reset to zero.")
        print(f"       Paid: {body.get('amount_paise')} paise")
    else:
        print(f"[FAIL] Settlement confirmation failed: {r.status_code} -> {body}")
        sys.exit(1)


def main():
    global SERVER, KEY_ID, AGENT_ID, RESOURCE_ID

    parser = argparse.ArgumentParser(description="AgentPay x402-INR Gateway Simulator")
    parser.add_argument("--server",   default="http://localhost:8080", help="Server base URL")
    parser.add_argument("--key",      required=True,                  help="Razorpay Key ID (rzp_test_...)")
    parser.add_argument("--agent",    default="simulator-agent-001",  help="Agent identifier")
    parser.add_argument("--resource", default="market-data-v1",       help="Resource ID to request")
    parser.add_argument("--skip-meter", action="store_true",          help="Skip metering flow")
    args = parser.parse_args()

    SERVER      = args.server
    KEY_ID      = args.key
    AGENT_ID    = args.agent
    RESOURCE_ID = args.resource

    print("\n  AgentPay Gateway Simulator")
    print(f"  Server  : {SERVER}")
    print(f"  Agent   : {AGENT_ID}")
    print(f"  Resource: {RESOURCE_ID}")

    # Phase 1: Get the 402 challenge
    order_id = phase_1_request_resource()

    # Phase 2: Pay via Razorpay Checkout (real browser automation)
    proof = phase_2_pay_via_checkout(order_id)

    # Phase 3: Retry with proof -- get the resource
    phase_3_retry_with_proof(proof)

    # Phase 4: Replay the same proof -- server must reject it
    phase_4_replay_attack(proof)

    # Phase 5: Usage metering demo
    if not args.skip_meter:
        phase_5_meter_flow()

    print("\n" + "="*60)
    print("  All phases completed successfully.")
    print("  AgentPay Gateway is working end-to-end.")
    print("="*60)


if __name__ == "__main__":
    main()
