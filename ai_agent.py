"""
ai_agent.py — Task-driven AI agent with tool-use reasoning loop.

The agent receives a natural-language task goal and autonomously:
  1. Plans which resources it needs to complete the task.
  2. Discovers resource price (handles 402).
  3. Decides whether to pay based on task priority and remaining budget.
  4. Executes payment via Razorpay Checkout.
  5. Accesses the resource and advances the task.
  6. Handles micro-usage billing ticks while working.
  7. Reports what it accomplished.

This is the "AI buyer" layer that the integration simulator (agent_simulator.py)
does not provide. The simulator is a deterministic protocol test; this module
is a goal-directed agent with reasoning and budget awareness.

Usage:
    python ai_agent.py --key rzp_test_xxx --task "Fetch BTC/INR market data"
    python ai_agent.py --key rzp_test_xxx --task "Analyse risk for portfolio XYZ" --budget 1000
"""

import argparse
import json
import sys
import time
import textwrap
import requests

if hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    except Exception:
        pass

SERVER  = "http://localhost:8080"
KEY_ID  = ""

# ── Resource catalog ──────────────────────────────────────────────────────────
# In production, this comes from a service discovery endpoint.
# Here we define the resources the agent knows about.
RESOURCE_CATALOG = {
    "market-data-v1": {
        "description": "Real-time market prices (BTC/INR, ETH/INR, equity indices)",
        "keywords":    ["market", "price", "btc", "eth", "crypto", "stock", "index", "data"],
        "value_score": 9,
    },
    "risk-engine-v1": {
        "description": "Portfolio risk analysis and VaR calculation",
        "keywords":    ["risk", "portfolio", "var", "analysis", "hedge"],
        "value_score": 8,
    },
    "news-feed-v2": {
        "description": "Curated financial news with sentiment scoring",
        "keywords":    ["news", "sentiment", "article", "report"],
        "value_score": 6,
    },
}

# ── Logging ───────────────────────────────────────────────────────────────────

def log(icon, msg, indent=0):
    prefix = "  " * indent
    print(f"  {icon} {prefix}{msg}")

def section(title):
    print(f"\n{'─'*60}")
    print(f"  {title}")
    print(f"{'─'*60}")

# ── Agent reasoning ───────────────────────────────────────────────────────────

class AgentBrain:
    """
    Minimal rule-based reasoning loop that mimics tool-use agent behavior.
    Can be extended to call an actual LLM (Gemini, GPT-4, Claude) by replacing
    the plan() and decide_to_pay() methods with API calls.
    """

    def __init__(self, task: str, budget_paise: int, agent_id: str):
        self.task          = task
        self.budget_paise  = budget_paise
        self.spent_paise   = 0
        self.agent_id      = agent_id
        self.memory        = []   # accumulated results
        self.reasoning_log = []   # decision trace

    @property
    def remaining_paise(self):
        return self.budget_paise - self.spent_paise

    def plan(self) -> list:
        """Return an ordered list of resource IDs relevant to the task."""
        task_lower = self.task.lower()
        ranked = []
        for rid, meta in RESOURCE_CATALOG.items():
            score = sum(1 for kw in meta["keywords"] if kw in task_lower)
            if score > 0:
                ranked.append((rid, score * meta["value_score"]))
        ranked.sort(key=lambda x: -x[1])
        plan = [r[0] for r in ranked] or list(RESOURCE_CATALOG.keys())[:1]
        self._think(f"Plan: will query {plan} to accomplish: '{self.task}'")
        return plan

    def decide_to_pay(self, resource_id: str, amount_paise: int) -> tuple:
        """
        Returns (should_pay: bool, reason: str).
        Reasoning: pay if the resource is relevant and the price fits the budget.
        """
        meta   = RESOURCE_CATALOG.get(resource_id, {})
        value  = meta.get("value_score", 5)
        budget = self.remaining_paise

        if amount_paise > budget:
            reason = f"Price {amount_paise}p > remaining budget {budget}p. Skipping."
            self._think(reason)
            return False, reason

        # Utility: pay if value-to-cost ratio is acceptable (≥1 INR per value point).
        ratio  = value / (amount_paise / 100) if amount_paise > 0 else 0
        if ratio >= 0.5:
            reason = f"Value score {value}, price ₹{amount_paise/100:.2f}, ratio {ratio:.1f}. Worth paying."
            self._think(reason)
            return True, reason
        else:
            reason = f"Value score {value} too low for price ₹{amount_paise/100:.2f}. Skipping."
            self._think(reason)
            return False, reason

    def record_result(self, resource_id: str, data: dict):
        self.memory.append({"resource": resource_id, "data": data})
        self._think(f"Stored result from {resource_id}: {list(data.keys())}")

    def synthesize(self) -> str:
        """Produce a final answer from accumulated memory."""
        if not self.memory:
            return "No resources accessed — task could not be completed within budget."
        parts = [f"Task: {self.task}", f"Budget used: ₹{self.spent_paise/100:.2f}", ""]
        for entry in self.memory:
            rid  = entry["resource"]
            meta = RESOURCE_CATALOG.get(rid, {})
            parts.append(f"[{rid}] {meta.get('description', '')}")
            data_pts = entry["data"].get("data_points", [])
            if isinstance(data_pts, str):
                try: data_pts = json.loads(data_pts)
                except: data_pts = [data_pts]
            for pt in data_pts:
                parts.append(f"  · {pt}")
        return "\n".join(parts)

    def _think(self, msg: str):
        self.reasoning_log.append(msg)
        log("🧠", msg, indent=1)

# ── Tool implementations ───────────────────────────────────────────────────────

def tool_discover_resource(agent_id: str, resource_id: str) -> dict:
    """GET resource without proof — returns the 402 body or the 200 body."""
    r = requests.get(f"{SERVER}/agent/resource/{resource_id}",
                     headers={"X-Agent-Id": agent_id},
                     timeout=10)
    return {"status_code": r.status_code, "body": r.json()}


def tool_pay_and_access(agent_id: str, resource_id: str,
                        order_id: str, amount_paise: int) -> dict:
    """Pay via Razorpay Checkout, then retry the resource request with proof headers."""
    log("💳", f"Paying ₹{amount_paise/100:.2f} for {resource_id} via Razorpay Checkout...")

    from playwright.sync_api import sync_playwright
    import threading, http.server, socket

    html = f"""<!DOCTYPE html><html><head><meta charset="utf-8">
<script src="https://checkout.razorpay.com/v1/checkout.js"></script>
</head><body style="background:#0f0f1a;margin:0">
<div id="r" style="padding:20px;font-family:monospace;color:#aaa">Waiting...</div>
<script>
window._done=undefined;
new Razorpay({{
    key:"{KEY_ID}",amount:"{amount_paise}",currency:"INR",
    name:"AgentPay Gateway",description:"Agent access: {resource_id}",
    order_id:"{order_id}",
    prefill:{{name:"AI Agent",email:"agent@agentpay.dev",contact:"+918077907751"}},
    handler:function(r){{document.getElementById('r').textContent=JSON.stringify(r);window._done=r;}},
    modal:{{ondismiss:function(){{window._done='dismissed';}}}},
    theme:{{color:"#6366f1"}}
}}).open();
</script></body></html>"""

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]

    class _H(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            b = html.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers(); self.wfile.write(b)
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
                if "razorpay" in f.url: frame = f; break

        if frame:
            try: frame.wait_for_load_state("networkidle", timeout=12000)
            except: pass
            page.wait_for_timeout(2000)

            # Dismiss mobile pre-fill overlay if present.
            try:
                frame.evaluate("""() => {
                    function set(el,v){Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el,v);
                    el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}
                    for(var i of document.querySelectorAll('input')){var p=i.placeholder||'';
                    if(p.toLowerCase().includes('mobile')||p.toLowerCase().includes('phone')){set(i,'8077907751');break;}}
                    for(var b of document.querySelectorAll('button')) if(b.textContent.trim()==='Continue'){b.click();return;}
                }""")
                page.wait_for_timeout(1500)
            except: pass

            try:
                frame.get_by_text("Netbanking").first.click(); page.wait_for_timeout(1500)
                frame.get_by_text("Bank of Baroda").first.click()
                for _ in range(16):
                    extras = [pg for pg in ctx.pages if pg != page]
                    if extras:
                        bp = extras[0]
                        try:
                            bp.wait_for_load_state("domcontentloaded", timeout=4000)
                            bp.get_by_role("button", name="Success").click(timeout=4000)
                            page.wait_for_timeout(2500); break
                        except: pass
                    page.wait_for_timeout(500)
            except: pass

        deadline = time.time() + 45
        while time.time() < deadline:
            result = page.evaluate("window._done")
            if result and result != "dismissed":
                proof = result; break
            page.wait_for_timeout(1000)

        browser.close()
    httpd.shutdown()

    if not proof:
        return {"error": True, "message": "Payment not completed."}

    # Retry resource with proof.
    r = requests.get(f"{SERVER}/agent/resource/{resource_id}", headers={
        "X-Agent-Id":            agent_id,
        "X-Razorpay-Payment-Id": proof["razorpay_payment_id"],
        "X-Razorpay-Order-Id":   proof["razorpay_order_id"],
        "X-Razorpay-Signature":  proof["razorpay_signature"],
    }, timeout=10)

    if r.status_code == 200:
        body = r.json()
        resource_data = body.get("resource", "{}")
        if isinstance(resource_data, str):
            try: resource_data = json.loads(resource_data)
            except: resource_data = {"raw": resource_data}
        return {"ok": True, "amount_paise": proof_amount(proof, r),
                "data": resource_data, "payment_id": proof["razorpay_payment_id"]}
    else:
        return {"error": True, "message": f"Resource access failed: {r.status_code} {r.text[:200]}"}


def tool_meter_tick(agent_id: str, resource_id: str,
                    tick_paise: int = 50, threshold_paise: int = 500) -> dict:
    """Send one usage tick. Returns status and whether settlement is required."""
    r = requests.post(f"{SERVER}/agent/meter/{resource_id}/tick",
                      headers={"X-Agent-Id": agent_id, "Content-Type": "application/json"},
                      json={"tick_paise": tick_paise, "threshold_paise": threshold_paise},
                      timeout=10)
    return {"status_code": r.status_code, "body": r.json()}


def proof_amount(proof, response):
    try: return response.json().get("amount_paise", 0)
    except: return 0

# ── Main agent loop ────────────────────────────────────────────────────────────

def run_agent(task: str, budget_paise: int, agent_id: str, skip_meter: bool):
    brain = AgentBrain(task, budget_paise, agent_id)

    section(f"AgentPay — AI Agent Starting")
    log("🎯", f"Task   : {task}")
    log("💰", f"Budget : ₹{budget_paise/100:.2f}")
    log("🤖", f"Agent  : {agent_id}")

    # Step 1: Plan which resources are needed.
    section("Step 1 — Planning")
    resource_plan = brain.plan()
    log("📋", f"Resources to query: {resource_plan}")

    # Step 2: For each resource — discover, decide, pay, access.
    for resource_id in resource_plan:
        section(f"Step 2 — Accessing '{resource_id}'")

        log("🔍", f"Discovering resource: {resource_id}")
        discovery = tool_discover_resource(agent_id, resource_id)
        sc   = discovery["status_code"]
        body = discovery["body"]

        if sc == 200:
            log("✅", "Resource is free — accessing directly.")
            brain.record_result(resource_id, body)
            continue

        if sc == 402:
            order_id    = body.get("razorpay_order_id", "")
            amount      = body.get("amount_paise", 0)
            log("💡", f"402 — payment required: ₹{amount/100:.2f} for '{resource_id}'")

            should_pay, reason = brain.decide_to_pay(resource_id, amount)
            if not should_pay:
                log("🚫", f"Skipping: {reason}")
                continue

            log("🛒", f"Decided to pay. Reason: {reason}")
            result = tool_pay_and_access(agent_id, resource_id, order_id, amount)

            if result.get("ok"):
                paid = result.get("amount_paise", amount)
                brain.spent_paise += paid
                log("✅", f"Access granted. Spent: ₹{paid/100:.2f} | Remaining: ₹{brain.remaining_paise/100:.2f}")
                brain.record_result(resource_id, result.get("data", {}))
            else:
                log("❌", f"Payment failed: {result.get('message')}")
        else:
            log("⚠️", f"Unexpected response: {sc}")

    # Step 3 (optional): demonstrate metered usage.
    if not skip_meter and resource_plan:
        primary = resource_plan[0]
        section(f"Step 3 — Metered usage on '{primary}'")
        log("📊", "Sending 5 usage ticks (50p each) to demonstrate metering...")
        for i in range(5):
            tick_result = tool_meter_tick(agent_id, primary)
            sc   = tick_result["status_code"]
            body = tick_result["body"]
            if sc == 200:
                acc = body.get("accumulated_paise", 0)
                log("↗️", f"Tick {i+1}: ₹{acc/100:.2f} accumulated", indent=1)
            elif sc == 402:
                log("📦", f"Threshold reached — settlement required.", indent=1)
                break
            time.sleep(0.2)

    # Step 4: Synthesise and report.
    section("Final Report")
    report = brain.synthesize()
    print()
    for line in report.split("\n"):
        print(f"    {line}")

    print()
    log("✅", f"Agent task complete. Total spent: ₹{brain.spent_paise/100:.2f}")
    print()


def main():
    global SERVER, KEY_ID

    p = argparse.ArgumentParser(description="AgentPay task-driven AI agent")
    p.add_argument("--key",        required=True, help="Razorpay Key ID")
    p.add_argument("--task",       default="Fetch market data for BTC/INR")
    p.add_argument("--budget",     type=int, default=500, help="Budget in paise")
    p.add_argument("--agent",      default="ai-agent-001")
    p.add_argument("--server",     default="http://localhost:8080")
    p.add_argument("--skip-meter", action="store_true")
    args = p.parse_args()

    SERVER = args.server
    KEY_ID = args.key

    run_agent(args.task, args.budget, args.agent, args.skip_meter)


if __name__ == "__main__":
    main()
