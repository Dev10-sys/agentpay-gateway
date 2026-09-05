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
import os
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

def log(tag, msg, indent=0):
    prefix = "  " * indent
    print(f"  {tag:<12} {prefix}{msg}")

def section(title):
    print(f"\n{'─'*60}")
    print(f"  {title}")
    print(f"{'─'*60}")

# ── Agent reasoning ───────────────────────────────────────────────────────────

class AgentBrain:
    """
    Cognitive decision engine for the AI buyer.
    Supports two operating modes:
      1. Live LLM Mode (OpenAI / Google Gemini):
         Uses an LLM-guided tool-use and reasoning loop when an API key is provided
         via OPENAI_API_KEY, GEMINI_API_KEY, or command-line flags.
      2. Deterministic Heuristic Engine (Zero-cost Mode):
         Uses bounded utility-maximization rules for deterministic tool planning
         and predictable offline testing.
    """

    def __init__(self, task: str, budget_paise: int, agent_id: str,
                 llm_provider: str = "auto", llm_key: str = None, model: str = None):
        self.task          = task
        self.budget_paise  = budget_paise
        self.spent_paise   = 0
        self.agent_id      = agent_id
        self.memory        = []   # accumulated results
        self.reasoning_log = []   # decision trace

        # Detect LLM provider & credentials
        self.llm_provider = llm_provider
        self.llm_key      = llm_key or os.environ.get("OPENAI_API_KEY") or os.environ.get("GEMINI_API_KEY")
        self.model        = model

        if self.llm_provider == "auto":
            if os.environ.get("OPENAI_API_KEY") or (self.llm_key and self.llm_key.startswith("sk-")):
                self.llm_provider = "openai"
            elif os.environ.get("GEMINI_API_KEY") or (self.llm_key and self.llm_key.startswith("AIza")):
                self.llm_provider = "gemini"
            else:
                self.llm_provider = "heuristic"

        if self.llm_provider == "openai" and not self.model:
            self.model = "gpt-4o-mini"
        elif self.llm_provider == "gemini" and not self.model:
            self.model = "gemini-1.5-flash"

        if self.llm_provider in ("openai", "gemini") and self.llm_key:
            self._think(f"Brain online: Live {self.llm_provider.upper()} Engine ({self.model})")
        else:
            self.llm_provider = "heuristic"
            self._think("Brain online: Heuristic Engine (Tip: set OPENAI_API_KEY or GEMINI_API_KEY for live LLM mode)")

    @property
    def remaining_paise(self):
        return self.budget_paise - self.spent_paise

    def _call_openai(self, prompt: str, system: str = "You are an autonomous AI economic procurement agent.") -> str:
        url = "https://api.openai.com/v1/chat/completions"
        headers = {
            "Authorization": f"Bearer {self.llm_key}",
            "Content-Type": "application/json"
        }
        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": prompt}
            ],
            "temperature": 0.2
        }
        resp = requests.post(url, headers=headers, json=payload, timeout=20)
        resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"]

    def _call_gemini(self, prompt: str, system: str = "") -> str:
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{self.model}:generateContent?key={self.llm_key}"
        payload = {
            "contents": [{"parts": [{"text": (system + "\n\n" + prompt).strip()}]}]
        }
        resp = requests.post(url, json=payload, timeout=20)
        resp.raise_for_status()
        return resp.json()["candidates"][0]["content"]["parts"][0]["text"]

    def plan(self) -> list:
        """Return an ordered list of resource IDs relevant to the task."""
        if self.llm_provider in ("openai", "gemini") and self.llm_key:
            try:
                catalog_desc = "\n".join([f"- {k}: {v['description']}" for k, v in RESOURCE_CATALOG.items()])
                prompt = (
                    f"You have the goal: '{self.task}'.\n"
                    f"Available merchant resources:\n{catalog_desc}\n\n"
                    "Which resource IDs are required to accomplish this goal? "
                    "Return ONLY a JSON array of valid resource IDs, e.g. [\"market-data-v1\"]."
                )
                raw = self._call_openai(prompt) if self.llm_provider == "openai" else self._call_gemini(prompt)
                clean = raw.strip()
                if "```" in clean:
                    clean = clean.split("```")[1]
                    if clean.startswith("json"): clean = clean[4:]
                plan = json.loads(clean.strip())
                if isinstance(plan, list) and plan:
                    valid = [r for r in plan if r in RESOURCE_CATALOG]
                    if valid:
                        self._think(f"LLM Plan: {valid} selected to solve '{self.task}'")
                        return valid
            except Exception as e:
                self._think(f"LLM plan error ({e}) — falling back to heuristic planner")

        # Heuristic fallback
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
            reason = f"Price ₹{amount_paise/100:.2f} exceeds remaining budget of ₹{budget/100:.2f}. Hard stop."
            self._think(reason)
            return False, reason

        if self.llm_provider in ("openai", "gemini") and self.llm_key:
            try:
                prompt = (
                    f"You are an autonomous AI buyer encountering an HTTP 402 payment challenge.\n"
                    f"Goal: '{self.task}'\n"
                    f"Resource: '{resource_id}' ({meta.get('description', '')})\n"
                    f"Price: ₹{amount_paise/100:.2f} ({amount_paise} paise)\n"
                    f"Remaining budget: ₹{budget/100:.2f} ({budget} paise)\n"
                    f"Should you authorize this transaction? Respond ONLY in valid JSON: "
                    f"{{\"pay\": true, \"reason\": \"<short reason>\"}}"
                )
                raw = self._call_openai(prompt) if self.llm_provider == "openai" else self._call_gemini(prompt)
                clean = raw.strip()
                if "```" in clean:
                    clean = clean.split("```")[1]
                    if clean.startswith("json"): clean = clean[4:]
                decision = json.loads(clean.strip())
                should_pay = bool(decision.get("pay", True))
                reason = f"LLM Decision: {decision.get('reason', 'Authorized by model.')}"
                self._think(reason)
                return should_pay, reason
            except Exception as e:
                self._think(f"LLM decision error ({e}) — using heuristic budget policy")

        # Heuristic ratio check
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

        if self.llm_provider in ("openai", "gemini") and self.llm_key:
            try:
                prompt = (
                    f"You are an AI financial analyst reporting on the completed goal: '{self.task}'.\n"
                    f"Budget used: ₹{self.spent_paise/100:.2f}.\n"
                    f"Data collected from paid merchant resources:\n{json.dumps(self.memory, indent=2)}\n\n"
                    "Provide a crisp, professional analytical report with actionable insights."
                )
                res = self._call_openai(prompt) if self.llm_provider == "openai" else self._call_gemini(prompt)
                return f"Task: {self.task}\nBudget spent: ₹{self.spent_paise/100:.2f} | Engine: {self.llm_provider.upper()} ({self.model})\n\n" + res.strip()
            except Exception as e:
                self._think(f"LLM synthesis error ({e}) — using structured summary")

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
        log("[REASON]", msg, indent=1)

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
    log("[CHECKOUT]", f"Authorizing ₹{amount_paise/100:.2f} for {resource_id} via Razorpay Checkout...")

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
    prefill:{{name:"AI Agent",email:"agent@agentpay.dev",contact:"8077907751"}},
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
                    var btns = Array.from(document.querySelectorAll('button')).filter(b => b.textContent.trim() === 'Continue');
                    if (btns.length > 0) btns[btns.length - 1].click();
                }""")
                page.wait_for_timeout(1500)
            except: pass

            try:
                frame.evaluate("""() => {
                    var overlays = document.querySelectorAll('.stack-overlay, #overlay-backdrop, [data-testid*="overlay"]');
                    for (var o of overlays) o.remove();
                }""")
            except: pass

            try:
                frame.get_by_text("Netbanking").first.click(force=True)
                page.wait_for_timeout(1500)
                bob = frame.get_by_text("Bank of Baroda").first
                bob.click(force=True)
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

def run_agent(task: str, budget_paise: int, agent_id: str, skip_meter: bool,
              llm_provider: str = "auto", llm_key: str = None, model: str = None):
    brain = AgentBrain(task, budget_paise, agent_id, llm_provider, llm_key, model)

    section("AgentPay Client — Procurement Execution")
    log("[TASK]", f"Task   : {task}")
    log("[BUDGET]", f"Budget : ₹{budget_paise/100:.2f}")
    log("[AGENT]", f"Agent  : {agent_id}")
    log("[ENGINE]", f"Engine : {brain.llm_provider.upper()} ({brain.model or 'heuristic'})")

    # Step 1: Plan which resources are needed.
    section("Step 1 — Planning")
    resource_plan = brain.plan()
    log("[PLAN]", f"Resources to query: {resource_plan}")

    # Step 2: For each resource — discover, decide, pay, access.
    for resource_id in resource_plan:
        section(f"Step 2 — Accessing '{resource_id}'")

        log("[DISCOVER]", f"Discovering resource: {resource_id}")
        discovery = tool_discover_resource(agent_id, resource_id)
        sc   = discovery["status_code"]
        body = discovery["body"]

        if sc == 200:
            log("[DIRECT]", "Resource is free — accessing directly.")
            brain.record_result(resource_id, body)
            continue

        if sc == 402:
            order_id    = body.get("razorpay_order_id", "")
            amount      = body.get("amount_paise", 0)
            log("[CHALLENGE]", f"HTTP 402 payment required: ₹{amount/100:.2f} for '{resource_id}'")

            should_pay, reason = brain.decide_to_pay(resource_id, amount)
            if not should_pay:
                log("[SKIP]", f"Skipping: {reason}")
                continue

            log("[DECISION]", f"Decided to pay. Reason: {reason}")
            result = tool_pay_and_access(agent_id, resource_id, order_id, amount)

            if result.get("ok"):
                paid = result.get("amount_paise", amount)
                brain.spent_paise += paid
                log("[SUCCESS]", f"Access granted. Spent: ₹{paid/100:.2f} | Remaining: ₹{brain.remaining_paise/100:.2f}")
                brain.record_result(resource_id, result.get("data", {}))
            else:
                log("[FAIL]", f"Payment failed: {result.get('message')}")
        else:
            log("[WARN]", f"Unexpected response: {sc}")

    # Step 3 (optional): demonstrate metered usage.
    if not skip_meter and resource_plan:
        primary = resource_plan[0]
        section(f"Step 3 — Metered usage on '{primary}'")
        log("[METER]", "Sending 5 usage ticks (50p each) to demonstrate metering...")
        for i in range(5):
            tick_amount = 50
            tick_result = tool_meter_tick(agent_id, primary, tick_paise=tick_amount)
            sc   = tick_result["status_code"]
            body = tick_result["body"]
            if sc == 200:
                acc = body.get("accumulated_paise", 0)
                brain.spent_paise += tick_amount
                log("[TICK]", f"Tick {i+1}: ₹{acc/100:.2f} accumulated | Added to spend: ₹{tick_amount/100:.2f} (Total: ₹{brain.spent_paise/100:.2f})", indent=1)
            elif sc == 402:
                log("[SETTLE]", f"Threshold reached — settlement required.", indent=1)
                break
            time.sleep(0.2)

    # Step 4: Synthesise and report.
    section("Final Report")
    report = brain.synthesize()
    print()
    for line in report.split("\n"):
        print(f"    {line}")

    print()
    log("[DONE]", f"Agent task complete. Total spent: ₹{brain.spent_paise/100:.2f}")
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
    p.add_argument("--llm",        choices=["auto", "openai", "gemini", "heuristic"], default="auto",
                   help="Brain engine: auto (detects env var), openai, gemini, or heuristic")
    p.add_argument("--llm-key",    help="API key for OpenAI or Gemini (or set OPENAI_API_KEY/GEMINI_API_KEY env var)")
    p.add_argument("--model",      help="Model name (e.g. gpt-4o-mini, gemini-1.5-flash)")
    args = p.parse_args()

    SERVER = args.server
    KEY_ID = args.key

    run_agent(args.task, args.budget, args.agent, args.skip_meter,
              llm_provider=args.llm, llm_key=args.llm_key, model=args.model)


if __name__ == "__main__":
    main()
