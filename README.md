# AgentPay Gateway

> **Razorpay Buildathon 2026 · Track 1 — AI Growth and Agentic Commerce**

AgentPay is a Razorpay-native machine-payment gateway that applies x402-style HTTP payment discovery to INR payments, with agent-level policy enforcement, auditable spending, and fiat-aware usage aggregation.

---

## The gap this fills

Razorpay's existing Agentic Payments product is designed for the **human-delegates-to-agent** pattern: a user authorises an AI to spend on their behalf.

AgentPay targets a **complementary, currently unserved case**: an AI agent discovers a monetised API resource, receives a machine-readable payment challenge, settles in INR via Razorpay, and obtains cryptographically-bound access — **no human in the loop at all**.

The protocol is inspired by the x402 standard (Linux Foundation, 40+ member companies including Visa, Mastercard, Google, AWS), adapted to INR bank rails instead of stablecoins. It is not a wire-compatible implementation of x402 v2; it is an **x402-style flow on Razorpay rails**, honest about that distinction.

---

## Architecture

```
AI Agent (agent_simulator.py / ai_agent.py)
    │
    │  GET /agent/resource/{id}           ← no proof headers
    │  ◄── HTTP 402
    │       payment_protocol: agentpay/x402-inspired
    │       razorpay_order_id: order_xxx
    │       amount_paise: 100
    │
    │  [decides to pay → Razorpay Checkout → captures payment]
    │
    │  GET /agent/resource/{id}           ← X-Razorpay-* headers attached
    │  ◄── HTTP 200 + unlocked resource
    │
    │  POST /agent/meter/{id}/tick        ← micro-usage accumulation
    │  ◄── HTTP 200 (accumulating) / 402 (threshold reached, settle now)
    │
    │  POST /webhook/razorpay             ← Razorpay webhook events
```

### Key files

| File | Purpose |
|---|---|
| `AgentDatabase.java` | SQLite schema bootstrap (4 tables + payment_event) |
| `AgentPolicyEngine.java` | Per-agent daily cap: `reserve → commit → release` lifecycle |
| `AuditLog.java` | Append-only decision log with VERIFIED-only partial unique index |
| `AgentGatewayResource.java` | x402-style gateway — `/agent/resource/{id}` |
| `UsageMeterResource.java` | Micro-usage accumulation — `/agent/meter/{id}/tick` |
| `WebhookResource.java` | Signature validation + genuine idempotency via `payment_event` table |
| `agent_simulator.py` | Deterministic 5-phase integration test (requests + Playwright) |
| `ai_agent.py` | Task-driven agent with tool-use reasoning loop |
| `Dockerfile` | Multi-stage container image |

---

## Setup

### 1. Razorpay Test-Mode Credentials

Sign up at [dashboard.razorpay.com](https://dashboard.razorpay.com) → Settings → API Keys → Generate Test Key.

Set as environment variables (never hardcode in source):
```powershell
$env:RAZORPAY_KEY_ID="rzp_test_YOUR_KEY_ID"
$env:RAZORPAY_SECRET="YOUR_SECRET_KEY"
$env:RAZORPAY_WEBHOOK_SECRET="YOUR_WEBHOOK_SECRET"   # optional
```

### 2. Build and Run (Java server)

```powershell
mvn package
java -jar target/razorpay-java-testapp-1.0-SNAPSHOT.jar server server.yml
```

Server live at `http://localhost:8080`.

### 3. Run the Integration Simulator

```powershell
pip install requests playwright
playwright install chromium
.\run_simulator.ps1
```

| Phase | What happens |
|---|---|
| 1 | `GET /agent/resource` → **HTTP 402** with Razorpay order |
| 2 | Playwright opens Checkout → selects Netbanking (test bank) → authorises payment |
| 3 | Same GET with proof headers → **HTTP 200** + unlocked resource |
| 4 | Replay the same proof → **HTTP 409 Conflict** |
| 5 | Tick metering (50p × 10 = 500p threshold) → consolidated order → settle → ledger reset |

### 4. Run the AI Agent Demo

```powershell
python ai_agent.py --key $env:RAZORPAY_KEY_ID --task "Fetch market data for BTC/INR"
```

---

## API Reference

### `GET /agent/resource/{resourceId}`

**Without proof** → `402 Payment Required`
```json
{
  "payment_protocol": "agentpay/x402-inspired",
  "status": "payment_required",
  "amount_paise": 100,
  "currency": "INR",
  "razorpay_order_id": "order_ABC123",
  "instructions": "Attach X-Razorpay-Payment-Id, X-Razorpay-Order-Id, X-Razorpay-Signature headers and retry."
}
```

**With proof** → `200 OK`
```json
{
  "status": "ok",
  "resource_id": "market-data-v1",
  "payment_id": "pay_XYZ",
  "amount_paise": 100
}
```

### `POST /agent/meter/{resourceId}/tick`

Body (optional, validated server-side): `{ "tick_paise": 50, "threshold_paise": 500 }`

Returns `200` while accumulating, `402` with `razorpay_order_id` when threshold crossed.  
Ledger resets **only** after payment is confirmed `captured` and the captured amount equals the accumulated balance.

### `POST /webhook/razorpay`

Validates `X-Razorpay-Signature`. Genuine idempotency: duplicate `payment.captured` deliveries are detected via `payment_event` table (UNIQUE on `event_type + payment_id`). Webhook events write `DECISION_WEBHOOK` — not `DECISION_VERIFIED` — so webhook delivery never causes a spurious 409 on the agent retry path.

---

## Budget / Policy Engine

```
available = daily_limit - daily_spent - reserved_paise
```

- **Reserve at challenge time** — budget is held before the Razorpay order is created.  
  An agent over its cap is rejected before any payment is attempted.
- **Commit on capture** — held amount moves to `daily_spent` after payment confirmed.
- **Release on expiry** — reservations from orders never paid are freed after 15 minutes.
- Each meter tick debits the daily budget immediately, so accumulated unsettled usage counts toward the cap.
- Day resets automatically on the first request after midnight.
- Default daily limit: ₹500 (50 000 paise) per agent.
- `BEGIN IMMEDIATE` transactions serialise concurrent budget mutations.

---

## Usage Metering

x402 on crypto allows sub-cent per-call charges because blockchain settlement is nearly free. INR bank rails have a practical minimum (~₹1). AgentPay solves this by accumulating small tick amounts in a server-side ledger and firing **one consolidated Razorpay order** only when the threshold is crossed.

Settlement semantics:
- Ledger resets to zero **only after** the consolidated order's payment is confirmed `captured`
- Captured amount must exactly equal accumulated balance — partial payments are rejected
- Settlement proof is validated the same way as gateway proof (HMAC + order notes binding)

---

## Security Model

| Guarantee | Mechanism |
|---|---|
| Budget reservation before payment | `reserve()` + `reserved_paise` column |
| Proof bound to exact resource + agent | `payment_challenge` table + live order notes check |
| Atomic double-spend prevention | `UPDATE ... WHERE status='PENDING'` inside BEGIN IMMEDIATE |
| Replay protection | Partial unique index on `audit_log` (VERIFIED rows only) |
| Abandoned reservation expiry | `purgeExpiredChallenges()` at challenge time |
| Webhook idempotency | `payment_event` UNIQUE(event_type, payment_id) |
| Input validation | tick/threshold bounds, negative amount rejection |

---

## Docker

```bash
docker build -t agentpay .
docker run -p 8080:8080 \
  -e RAZORPAY_KEY_ID=rzp_test_xxx \
  -e RAZORPAY_SECRET=xxx \
  -e RAZORPAY_WEBHOOK_SECRET=xxx \
  agentpay
```

---

## Tests

```powershell
mvn test
```

15 focused unit tests across `AgentPolicyEngineTest` and `AuditLogTest`:
- Policy: reservation success/failure, cap exhaustion, blocked agent, zero/negative input
- Concurrency: simultaneous reservation against exact budget — only one wins
- Audit: VERIFIED vs WEBHOOK isolation, duplicate-insert swallowed by index
