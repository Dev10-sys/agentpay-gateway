# AgentPay Gateway

[![CI](https://github.com/Dev10-sys/agentpay-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/Dev10-sys/agentpay-gateway/actions)

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
| `AgentDatabase.java` | SQLite schema bootstrap (5 core tables) |
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

### 4. Run the Autonomous AI Agent (Track 1)

`ai_agent.py` acts as an autonomous economic buyer navigating the 402 payment protocol:
- **Live LLM Engine**: Uses an LLM-guided tool-use loop with OpenAI (default `gpt-4o-mini`, configurable via `--model`) or Google Gemini (default `gemini-1.5-flash`) for dynamic planning, economic utility evaluation, and analytical synthesis when an API key is provided.
- **Zero-Cost Heuristic Engine**: Runs offline with deterministic utility-scoring rules for reproducible tests and demonstrations without external dependencies.

```powershell
# Offline / Heuristic Mode
.\run_agent.ps1 --task "Analyze market prices for BTC/INR"

# Live LLM Reasoning Mode (OpenAI / Gemini)
$env:OPENAI_API_KEY="sk-..."
.\run_agent.ps1 --task "Assess portfolio risk and calculate VaR" --llm openai
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
Both the daily budget debit and usage ledger update are executed within a single ACID transaction. If an unsettled order is pending, `402` is returned immediately without debiting the daily budget.

### `POST /webhook/razorpay`

Validates `X-Razorpay-Signature`. Deduplication uses official `X-Razorpay-Event-Id` and `UNIQUE(event_type, payment_id)` in the `payment_event` table. Database errors return HTTP 500 to trigger Razorpay retries, while processed duplicates safely return HTTP 200. Webhook events write `DECISION_WEBHOOK` — never `DECISION_VERIFIED` — isolating webhooks from the replay protection index.

---

---

## Dual-Layer Budget Architecture

AgentPay intentionally separates client-side economic planning from server-side policy enforcement:

1. **Client-Side Autonomous Budget (`ai_agent.py`)**:
   - Acts as the agent's internal cognitive constraint.
   - Evaluates task ROI and economic utility before agreeing to pay an HTTP 402 challenge.
   - Tracks `budget_paise` and `spent_paise` to decide when to stop or prioritize queries.

2. **Server-Side Hard Policy Enforcement (`AgentPolicyEngine`)**:
   - Non-bypassable security gateway implemented on the merchant side.
   - Formula: `available = daily_limit_paise - daily_spent_paise - reserved_paise`.
   - **Reserve at challenge time** — budget is held in DB before creating the Razorpay order. Agents exceeding limits are rejected before payment.
   - **Commit on capture** — held funds transfer from `reserved_paise` to `daily_spent_paise` upon successful payment verification.
   - **Lazy expiry purging** — unconsumed reservations older than 15 minutes are purged in an atomic transaction during challenge issuance, releasing held budget.
   - **Atomic micro-metering** — meter ticks and daily budget consumption commit together in a single ACID transaction; pending settlement orders do not drain budget.
   - **Midnight reset** — resets daily spend and persists `daily_spent_paise = 0` in SQLite on the first request of each new calendar day.
   - **Concurrency control** — serialized SQLite transactions prevent parallel over-allocation.

---

## Security Model

| Guarantee | Mechanism | Scope |
|---|---|---|
| Atomic Challenge Settlement | `UPDATE payment_challenge SET status='CONSUMED' WHERE order_id=? AND status='PENDING'` | Prevents double-spend and concurrent replay |
| Pre-Flight Budget Hold | `reserve()` + `reserved_paise` column | Blocks checkout if daily limit exhausted |
| Cryptographic Identity Binding | Live Razorpay Order `notes` verification | Prevents cross-agent or cross-resource reuse |
| Strict Financial Consistency | `payment.amount == order.amount == RESOURCE_PRICE_PAISE` | Guarantees exact INR captured matches price |
| Replay Protection | Partial unique index on `audit_log` (`WHERE decision='VERIFIED'`) | Blocks re-submission of already-verified proof |
| Lazy Expiry Cleanup | Atomic `purgeExpiredChallenges()` during challenge creation | Prevents unfulfilled 402 holds from locking budget |
| Atomic Micro-Metering | Single-transaction ledger write + budget debit | Prevents accounting drift and repeated drain |
| Webhook Idempotency | `X-Razorpay-Event-Id` + `payment_event` table; HTTP 500 on DB error | Deduplicates retries without event loss |
| Request Guardrails | Server-side tick/threshold bounds & 40-character receipt limits | Prevents malformed or overflowing inputs |

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

**21 focused state-machine and policy tests** across `AgentPolicyEngineTest` (16 tests) and `AuditLogTest` (5 tests):
- **Policy lifecycle**: Reservation success/failure, cap exhaustion, blocked agent, zero/negative inputs.
- **Atomic challenge settlement**: Single-winner guarantee on `atomicConsumeChallenge`, duplicate-consumption rejection (409).
- **Midnight reset**: SQLite database verification confirming `daily_spent_paise` resets to 0 across day rollover.
- **Atomic metering**: Transactional rollback test ensuring failed ledger writes roll back budget debits.
- **Expiry cleanup**: Atomic challenge expiration and budget reservation release.
- **Concurrency**: Parallel race-condition tests confirming single winner when budget equals exact request amount.
- **Audit & Webhooks**: Replay prevention, VERIFIED vs WEBHOOK isolation, `X-Razorpay-Event-Id` duplicate handling.
