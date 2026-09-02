# AgentPay Gateway

> **Razorpay Buildathon 2026 · Track 1 — AI Growth and Agentic Commerce**

AgentPay is a Razorpay-native machine-payment gateway that applies HTTP-402-style payment discovery to INR payments, with agent-level policy enforcement, auditable spending, and fiat-aware usage aggregation.

---

## The gap this fills

Razorpay's existing Agentic Payments product is designed for the **human-delegates-to-agent** pattern: a user authorises an AI to spend on their behalf.

AgentPay targets a different, currently unaddressed case: **one machine paying another machine or API directly, with no human in the loop at all** — the same case the x402 protocol (Linux Foundation, 40 member companies including Visa, Mastercard, Google, AWS) was built for globally, applied here to INR bank rails instead of stablecoins.

---

## Architecture

```
AI Agent (agent_simulator.py)
    │
    │  GET /agent/resource/{id}          ← no proof headers
    │  ◄── HTTP 402 + Razorpay order JSON
    │
    │  [pays via Razorpay Checkout]
    │
    │  GET /agent/resource/{id}          ← X-Razorpay-* headers attached
    │  ◄── HTTP 200 + unlocked resource
    │
    │  POST /agent/meter/{id}/tick       ← micro-usage accumulation
    │  ◄── HTTP 200 (accumulating) / 402 (threshold crossed, settle now)
    │
    │  POST /webhook/razorpay            ← Razorpay webhook events
```

### New files added (existing files untouched except App.java line 30–33)

| File | Purpose |
|---|---|
| `AgentDatabase.java` | SQLite schema bootstrap (3 tables) |
| `AgentPolicyEngine.java` | Per-agent daily spend cap with atomic `BEGIN IMMEDIATE` transactions |
| `AuditLog.java` | Immutable per-request ledger with idempotency via unique index on `payment_id` |
| `AgentGatewayResource.java` | HTTP-402 gateway — `/agent/resource/{id}` |
| `UsageMeterResource.java` | Micro-usage accumulation — `/agent/meter/{id}/tick` |
| `WebhookResource.java` | Webhook validation via `Utils.verifyWebhookSignature` |
| `agent_simulator.py` | Python agent: requests → Playwright checkout → retry → replay test |
| `Dockerfile` | Multi-stage container image |

---

## Setup

### 1. Razorpay Test-Mode Credentials

Sign up at [dashboard.razorpay.com](https://dashboard.razorpay.com) → Settings → API Keys → Generate Test Key.

Edit `server.yml`:
```yaml
apiKey: rzp_test_YOUR_KEY_ID
secretKey: YOUR_SECRET_KEY
webhookSecret: YOUR_WEBHOOK_SECRET   # optional, set in Dashboard → Webhooks
```

### 2. Build and Run (Java server)

```powershell
# Build the fat jar
mvn package -DskipTests

# Start the server
java -jar target/razorpay-java-testapp-1.0-SNAPSHOT.jar server server.yml
```

Server is live at `http://localhost:8080`.

### 3. Run the Python Simulator

```bash
pip install requests playwright
playwright install chromium

python agent_simulator.py --key rzp_test_YOUR_KEY_ID
```

The simulator runs all five phases automatically:

| Phase | What happens |
|---|---|
| 1 | `GET /agent/resource/market-data-v1` → **HTTP 402** with Razorpay order |
| 2 | Playwright opens Checkout → selects Netbanking (test bank) → authorizes payment |
| 3 | Same GET with proof headers → **HTTP 200** + unlocked resource |
| 4 | Replay the same proof → **HTTP 409 Conflict** (graceful rejection) |
| 5 | Tick metering (50 paise × 10 = 500 paise threshold) → settlement order → pay → ledger reset |

---

## API Reference

### `GET /agent/resource/{resourceId}`

**Without proof headers** → `402 Payment Required`
```json
{
  "x402_version": "1.0-INR",
  "status": "payment_required",
  "amount_paise": 100,
  "currency": "INR",
  "razorpay_order_id": "order_ABC123",
  "instructions": "Attach X-Razorpay-Payment-Id, X-Razorpay-Order-Id, X-Razorpay-Signature headers and retry this GET request."
}
```

**With proof headers** → `200 OK`
```json
{
  "status": "ok",
  "resource_id": "market-data-v1",
  "resource": "{ ... }",
  "payment_id": "pay_XYZ",
  "amount_paise": 100
}
```

### `POST /agent/meter/{resourceId}/tick`

Body (optional): `{ "tick_paise": 10, "threshold_paise": 100 }`

Returns `200` while accumulating, `402` with `razorpay_order_id` when threshold crossed.  Ledger resets to zero **only** after the order is confirmed captured.

### `POST /webhook/razorpay`

Validates `X-Razorpay-Signature`, enforces idempotency, processes `payment.captured`.

### `POST /agent/resource/{resourceId}/verify`

Debug endpoint for curl/Postman (not the canonical path):
```bash
curl -X POST http://localhost:8080/agent/resource/market-data-v1/verify \
  -H 'Content-Type: application/json' \
  -d '{"agent_id":"test","razorpay_payment_id":"pay_xxx","razorpay_order_id":"order_xxx","razorpay_signature":"xxx"}'
```

---

## Policy Engine

- Default daily budget: **₹500 (50 000 paise)** per agent
- Budget check uses `BEGIN IMMEDIATE` SQLite transaction — concurrent requests cannot race past the cap
- Day resets automatically on the first request after midnight
- To block an agent or change limits: call `AgentPolicyEngine.upsertPolicy(agentId, false, 0)` or add an admin endpoint

---

## Usage Metering — the key differentiator

x402 on crypto allows sub-cent per-call charges because blockchain settlement is nearly free.  INR bank rails have a practical minimum (~₹1).  Rather than rejecting micro-usage, AgentPay accumulates small tick amounts in a server-side ledger and fires one consolidated Razorpay order only when the threshold is crossed.

Settlement semantics (non-negotiable):
- Ledger resets to zero **only after** the consolidated order's payment is confirmed `captured`
- If payment fails or is pending, the accumulated total **stays** and keeps growing until the next successful settlement — it is never reset on an unsuccessful attempt

---

## Docker

```bash
docker build -t agentpay .
docker run -p 8080:8080 \
  -e RAZORPAY_API_KEY=rzp_test_xxx \
  -e RAZORPAY_SECRET_KEY=xxx \
  agentpay
```
