# PayFlash: Digital Wallet + Flash-Sale Engine

A UPI-style digital wallet and flash-sale engine built with **Java 21 and Spring Boot 3.5**. It is designed for
**correctness under concurrency**: money is never lost or double-spent, and a flash sale never oversells, even
when thousands of requests arrive at once.

> **Status:** all five roadmap phases are complete.
> - **Wallet:** double-entry ledger, row locks, idempotency keys, transactional outbox → Kafka.
> - **Payments and protection:** Razorpay top-ups with HMAC-verified webhooks; Redis rate limiting.
> - **Flash sale:** Redis Lua + Kafka, **load-tested at ~4,800 req/s with zero oversell**.
> - **Frontend:** React, with live stock over WebSocket.
> - **Services:** a separate notification service with its own database, behind **Spring Cloud Gateway**.
> - **Observability:** **Prometheus + Grafana** dashboards and **end-to-end Zipkin traces** across HTTP and Kafka.
> - **Ready for real users:** email verification, forgot/reset password, "log out everywhere", account deletion,
>   Terms / Privacy / Refund pages, daily database backups, uptime and Prometheus alerts, and a security review
>   with every finding fixed or documented ([Security](#security)).
>
> 63 automated tests. The whole system (14 containers) starts with one `docker compose up`.

**Jump to:** [Run it](#run-it-everything-in-docker) · [Architecture](#architecture) ·
[Design decisions](#key-design-decisions-interview-talking-points) · [Security](#security) · [Load test](#load-test) ·
[Deploy](DEPLOY.md)

---

## Tech stack

| Area | Tech |
|---|---|
| Language / framework | Java 21 (virtual threads), Spring Boot 3.5, Spring Web, Spring Data JPA |
| Security | Spring Security, stateless JWT (access + refresh tokens), BCrypt, role-based access |
| Database | PostgreSQL 16, Flyway migrations |
| Messaging | Apache Kafka (KRaft) via a **Transactional Outbox**; consumer with retries + dead-letter topic |
| Payments | Razorpay (test mode): Orders API via Spring `RestClient`, HMAC-verified checkout + webhooks |
| Rate limiting | Redis + Bucket4j (distributed token buckets) |
| Flash sale | Redis Lua scripts (atomic stock), Kafka order queue, STOMP WebSocket live stock, k6 load test |
| Email | Spring Mail; Mailpit catches emails locally |
| Services | Spring Cloud Gateway (routing, Resilience4j circuit breakers, request ids), notification service with its own DB |
| Observability | Micrometer → **Prometheus + Grafana** (provisioned dashboard), **Zipkin** distributed tracing (Brave, W3C), Actuator |
| API docs | springdoc-openapi (Swagger UI) |
| Frontend | React 19, TypeScript, Vite, Tailwind CSS 4, TanStack Query, React Router, STOMP.js; served by nginx |
| Testing | JUnit 5, AssertJ, MockMvc, **Testcontainers** (real Postgres, Redis, Kafka), k6 |
| DevOps | Docker (multi-stage, BuildKit cache), Docker Compose (dev + production override), GitHub Actions CI (4 jobs) |

## Architecture

```
 Browser ──► web (nginx: React app) ──► gateway (Spring Cloud Gateway) ──┬──► app (core, Spring Boot) ──┬──► PostgreSQL (payflash)
           http://localhost:3000        circuit breakers, X-Request-Id   │    auth · wallet · payments   ├──► Redis (rate limits, sale stock)
                                                                          │    sales · outbox            └──► Kafka ─┐
                                                                          │         ▲ Razorpay API / webhook        │ wallet.transactions
                                                                          └──► notification-service ◄───────────────┘
                                                                               (own PostgreSQL) ──► SMTP (Mailpit)

 Every service ──► Prometheus ──► Grafana (dashboards)      Every service ──► Zipkin (one trace per request, across Kafka)
```

| Component | Responsibility |
|---|---|
| `app` (this repo's root) | Auth/JWT, wallet + ledger, Razorpay, flash sales, outbox publisher. A modular monolith: packages `auth`, `wallet`, `payment`, `sale`, `ratelimit`, `outbox`, `common`, `config` |
| `services/notification-service` | Consumes `wallet.transactions`, sends email, serves `GET /api/v1/notifications`. **Own database**, validates JWTs itself |
| `services/api-gateway` | Single entry point; routes `/api/v1/notifications/**` to the notification service and everything else to `app`; circuit breakers with JSON 503 fallbacks |
| `frontend` | React app, served by nginx, which proxies `/api` and `/ws` to the gateway |

**Why only one service was extracted:** the notification module already talked to the rest of the code only
through Kafka, so splitting it out changed one thing in the core. The event now carries the people involved
(user id, email, name), so the service never reads the wallet database (*event-carried state transfer*).
Wallet, payments and sales share transactions (a sale payment debits a wallet atomically), so splitting
those would trade database transactions for a saga, which is more complexity than this project needs.
That is a deliberate trade-off, not a missing step.

### Flash-sale flow

```
reserve ──► SaleCatalog (memory) ──► Redis Lua: already a buyer? pop a free unit ──► Kafka sale.orders ──► 202 PROCESSING
                                       (one atomic step, no locks)                         │
SaleOrderListener ◄────────────────────────────────────────────────────────────────────────┘ INSERT sale_orders (HELD)
pay      ──► lock order row ──► wallet PURCHASE (debit wallet, credit merchant) ──► PAID
sweeper  ──► HELD past expires_at (FOR UPDATE SKIP LOCKED) ──► EXPIRED ──► unit back to the Redis pool
broadcaster ──► every 500 ms: units left (Redis LLEN) ──► STOMP /topic/sales/{id} ──► browsers
```

### Razorpay top-up flow

```
Browser                      PayFlash                              Razorpay
  │ POST /payments/topup-orders ──► create order (Orders API) ─────────►│
  │ ◄── {orderId, keyId, amount}     store payment_orders(CREATED)       │
  │ ── Checkout.js: user pays ──────────────────────────────────────────►│
  │ POST /payments/verify ─────────► check HMAC(order|payment)           │
  │                                  lock order row → credit wallet once │
  │                                  ◄── POST /payments/webhook ─────────│ (also arrives, even if tab closed)
  │                                      check HMAC(raw body) → order already PAID → no-op
```

## Key design decisions (interview talking points)

1. **Double-entry ledger.** Every money movement writes balanced `DEBIT` + `CREDIT` rows into an append-only `ledger_entries` table. `GET /api/v1/admin/reconciliation` proves each cached wallet balance equals `SUM(credits) − SUM(debits)`.
2. **Pessimistic row locks in a fixed order.** Transfers `SELECT … FOR UPDATE` both wallets in **ascending id order**, so A→B and B→A running at the same time can't deadlock. A DB `CHECK (balance >= 0)` is the last line of defence against overdrafts.
3. **Idempotency keys.** Every top-up or transfer requires an `Idempotency-Key` header, and `UNIQUE (initiated_by, idempotency_key)` is enforced in the DB. The key is checked *after* the sender's row lock is held, so concurrent retries are serialized: one executes and the rest replay the stored result (`Idempotent-Replayed: true`). Reusing a key with a different payload returns `422`.
4. **Transactional Outbox.** The `TransactionCompleted` event is stored in `outbox_events` **in the same DB transaction** as the transfer. A scheduled publisher sends it to Kafka, using `FOR UPDATE SKIP LOCKED` so several instances can run safely. There are no "DB committed but event lost" bugs. Delivery is at-least-once, and consumers dedupe on `reference`.
5. **A real bug the tests caught.** Loading a `Wallet` entity *before* locking it made Hibernate return the stale, already-managed instance instead of the locked row, which caused `StaleObjectStateException` under load. The fix is to fetch only the id (`findIdByUserId`), then lock. The concurrency tests prove it.
6. **Money as `NUMERIC(19,2)` / `BigDecimal`**, never `double`. Amounts are validated for scale and range.
7. **Razorpay: credit exactly once, from two racing sources.** The browser's verify call and Razorpay's
   webhook usually arrive at the same moment. Both lock the `payment_orders` row (`SELECT … FOR UPDATE`);
   the loser waits, then sees `PAID` and does nothing. As a second guard, the wallet credit uses the
   idempotency key `rzp:<order_id>`. A test fires 10 verifies and 10 webhooks at once: one credit.
8. **Webhook security.** The HMAC is computed over the **raw request bytes** (re-serialising the JSON would
   change it), compared in constant time, and the paid amount must match the order. A wrong amount is
   logged and never credited. The webhook is the source of truth: it arrives even if the user closes the tab.
9. **No DB transaction around the Razorpay HTTP call.** Holding a pooled connection for up to 10 s while
   waiting on a third party would drain the pool under load. The order row is written after the call returns.
10. **Distributed rate limiting (Redis + Bucket4j).** Limits live in Redis, so they hold across all
    instances. Login and signup are limited per IP (slowing down credential stuffing). Money endpoints are
    limited per user, so an office of users behind one IP doesn't share a bucket. It **fails open**: if
    Redis is down, requests go through, because money safety comes from locks and idempotency, not the limiter.
11. **Idempotent Kafka consumer.** Kafka delivers at-least-once, so `notifications` has
    `UNIQUE (event_reference, user_id, channel)` and a redelivered event sends no second email. Failures
    retry with exponential backoff, then go to `wallet.transactions.DLT`. Malformed JSON skips the
    retries. An SMTP outage doesn't mark the app DOWN (mail health check disabled).
12. **Flash sale: Redis decides, Postgres records.** Units sit in a Redis list. One Lua script checks "already a
    buyer?", pops a free unit and registers the buyer in a single atomic step, because Redis runs a script
    without interleaving other commands. A list of unit numbers (rather than a counter) means every grant
    carries a distinct unit number, which the database can check.
13. **Kafka absorbs the burst.** The reserve endpoint touches no database: memory (sale cache) → Redis → Kafka →
    `202 PROCESSING`. A consumer writes `sale_orders` at its own pace, so 5,000 buyers per second don't become
    5,000 Postgres transactions per second. The client polls `GET /sales/orders/{holdToken}` until `HELD`.
14. **Database as the last safety net.** Partial unique indexes on `(sale_id, unit_no)` and `(sale_id, user_id)`
    `WHERE status IN ('HELD','PAID')` make an oversell or a double purchase impossible even if Redis were wrong.
15. **Holds expire safely.** A sweeper claims expired holds with `FOR UPDATE SKIP LOCKED`, so it skips a hold
    that is being paid right now (the pay transaction holds the row lock) and several instances can sweep in
    parallel. The unit goes back to Redis **only after the commit**. The release script is idempotent: it pushes
    the unit back only if the buyer was still registered, so a double release can't duplicate a unit.
16. **Recoverable.** If Redis loses its data, the pool is rebuilt from `sale_orders` on startup (or through
    `POST /api/v1/admin/sales/{id}/rebuild-inventory`). Redis also runs with an append-only file.
17. **Live stock without a message storm.** The stock count is read from Redis and pushed over WebSocket at
    most twice a second per sale, rather than once per reservation.
18. **Measured, then fixed.** The load test plus a Java Flight Recorder profile found that `JwtService` built a
    new JWT parser on every request, which serialised requests on a class-loader lock. Reusing one parser fixed
    it. See [load/RESULTS.md](load/RESULTS.md).
19. **Database per service.** The notification service has its own PostgreSQL. User ids there are plain numbers,
    not foreign keys. The core app's `notifications` table was dropped by migration `V4`.
20. **Each service verifies JWTs itself.** The notification service is an OAuth2 resource server that checks the
    HS256 signature locally (no call to the core app per request) and rejects refresh tokens used as access
    tokens. The next hardening step is RS256 + JWKS, so only the issuer holds the signing key.
21. **Gateway resilience.** Each route has a Resilience4j circuit breaker (opens at 50% failures over 20 calls)
    and a JSON fallback, so a dead service fails fast with a readable `503` instead of hanging every caller.
    `X-Forwarded-For` is only trusted from private networks, so clients can't spoof their IP to dodge rate limits.
22. **Tracing across the asynchronous gap.** The outbox stores the request's W3C `traceparent`. The scheduled
    publisher restores it, so **one Zipkin trace** shows gateway → app → outbox publish → Kafka →
    notification-service. Every response carries `X-Trace-Id` to paste into Zipkin.
23. **Metrics that mean something.** Besides latency histograms (p95/p99 in Grafana), the services export
    business metrics: reservations by outcome, sale payments, expired holds, wallet transactions by type,
    outbox backlog, rate-limit rejections, emails sent, Kafka consumer lag and open circuit breakers.
24. **Account security without server sessions.** JWTs stay stateless, but each carries the user's *token
    version*. A password reset, "log out everywhere" or account deletion bumps it: refresh tokens are checked
    against the database, and access tokens against Redis (one key per user, kept for one access-token
    lifetime), so old sessions die within seconds instead of after 15 minutes.
25. **One-time links that can't leak.** Verification and reset tokens are 256-bit random values; only their
    SHA-256 hash is stored, and each works once. The link puts the token after `#`, which browsers never send
    to a server, so it can't end up in access logs or `Referer` headers. These emails are sent by the core app
    directly (after the transaction commits), not through Kafka, so the tokens never sit in the outbox or a topic.
26. **Deletion that keeps the books balanced.** Ledger rows must survive, so a deleted account is anonymised
    (name, email and password removed, all sessions revoked) rather than removed, its wallet must be empty, and an
    `AccountDeleted` event tells the notification service to delete its copy of the user's data.

## Security

An independent review of the whole codebase was run before opening the app to real users. What it checked
and what changed:

| Area | Protection |
|---|---|
| Passwords | bcrypt; 8-72 characters; **account locked for 15 minutes after 5 wrong passwords** (stops guessing spread over many IPs); login takes the same time whether or not the email exists |
| Sessions | 15-minute access tokens, 7-day refresh tokens, HS256 pinned, token type checked; **revocable** (reset, log out everywhere, deletion) |
| Email ownership | Adding, sending or spending money needs a **verified email**; links are single-use, hashed, expiring |
| Enumeration | "Forgot password" answers the same for unknown emails |
| Flash-sale abuse | **One hold per person per sale**, even if it expired, so bots can't re-hold stock they never pay for; verified email required to reserve |
| Live stock socket | Clients may only **subscribe** to `/topic/sales/{id}`; sending fake stock messages to other viewers is rejected |
| Admin | The bootstrap admin is only ever **created**, never promoted from an existing account someone else could have registered |
| Payments | Razorpay HMACs compared in constant time over the raw body; amount and currency checked; credit exactly once. Client idempotency keys can't collide with internal ones |
| Browser | Strict **Content-Security-Policy** (own scripts + Razorpay only), no framing, nosniff, referrer policy, on every page; no `innerHTML` anywhere |
| Input | Validation on every request; names restricted to letters (they appear in other people's emails); malformed requests get 4xx, not 500 |
| Rate limits | Per IP for auth, per user for money and everything else, in Redis (shared by all instances) |
| Production safety | `PRODUCTION=true` makes the app **refuse to start** with the public dev JWT key, simulated top-ups, email off or a localhost URL. Prod compose: only port 80 published, Redis password, Swagger off, admin UIs on 127.0.0.1 |
| Data | Database per service; daily backups; deletion removes personal data everywhere |

Known limits, and the next steps for a high-value deployment:
- Tokens are kept in `localStorage` (the CSP makes stealing them through XSS much harder). The next step is an
  `HttpOnly` cookie for the refresh token, with rotation and reuse detection.
- Both services verify JWTs with one shared HS256 key; RS256 + JWKS would keep the signing key in the core app only.
- Signup still says when an email is already registered (a deliberate usability trade-off; rate limited per IP).
- Run `npm audit` and an OWASP dependency check in CI.

## Frontend

`frontend/` is a React + TypeScript app (Vite, Tailwind, TanStack Query):

| Screen | What it shows |
|---|---|
| Login / Sign up | JWT auth. The access token refreshes automatically on a 401 (concurrent 401s share one refresh call) |
| Wallet | Balance, recent activity, live sales |
| Send money | Transfer by email. One `Idempotency-Key` per attempt, so a double-click or a retry never pays twice |
| Add money | Razorpay Checkout (test mode, loaded on demand) or the instant demo top-up |
| History | Paginated transactions (top-ups, transfers, purchases) |
| Flash sales | Cards with **live stock bars** and countdowns (one shared STOMP connection) |
| Sale page | Big live counter → *Buy now* → `PROCESSING` (polls) → `HELD` with a pay-by countdown → *Pay* → `PAID` |
| My orders / Admin | Order history; admins create sales and check ledger reconciliation |

In Docker, nginx serves the built app and forwards `/api` and `/ws` to the gateway, so the browser sees one
origin and needs no CORS setup. For frontend development with hot reload: `cd frontend && npm install && npm run dev`
(http://localhost:5173; Vite forwards `/api` and `/ws` to `localhost:8090`).

## Run it (everything in Docker)

**Prerequisite:** Docker Desktop. That's all: every service is compiled inside Docker, and every dependency
runs as a container. Give Docker Desktop at least 6 GB of memory (*Settings → Resources*).

```bash
docker compose up -d --build      # first build takes a few minutes; later ones are cached
docker compose ps                 # wait until app, gateway, notification-service show (healthy)
docker compose logs -f app        # follow the app's logs
docker compose down               # stop (data is kept);  add -v to wipe all data
```

A local admin account is created automatically: `admin@payflash.dev` / `admin-password-1`. Override it with
`ADMIN_EMAIL` / `ADMIN_PASSWORD` in a `.env` file next to `docker-compose.yml`. These credentials are for local use only.

**New accounts must verify their email** before adding or spending money. Locally the email lands in Mailpit:
open http://localhost:8025 and click the link. Forgot-password emails arrive there too.

**Working in IntelliJ instead?** Run `docker compose stop app`, then start `PayFlashApplication` (JDK 21). It
connects to the same containers through the published ports. Run the tests with `mvnw.cmd verify` or
*Run 'All Tests'*. Docker must be running, because Testcontainers starts its own Postgres, Redis and Kafka.

| URL | What |
|---|---|
| **http://localhost:3000** | **The web app** (log in as the admin above to create a flash sale) |
| http://localhost:3001 | **Grafana**: the *PayFlash overview* dashboard (traffic, latency, sales, outbox, Kafka lag, ...) |
| http://localhost:9411 | **Zipkin**: paste a response's `X-Trace-Id` to see the request across all services |
| http://localhost:9090 | Prometheus (raw metrics, all 3 targets) |
| http://localhost:8088 | API gateway (what nginx talks to) · `/actuator/gateway/routes` lists the routes |
| http://localhost:8090/swagger-ui.html | Interactive API docs (click **Authorize** and paste the access token) |
| http://localhost:8085 | Kafka UI: watch `wallet.transactions` and `sale.orders` |
| http://localhost:8025 | Mailpit: every email the app sends lands here (verification, password reset, receipts) |
| `./backups/` | Daily database dumps written by the `backup` container |
| http://localhost:8090/razorpay-test.html | Dev page to pay a real Razorpay test-mode order |

### Razorpay test mode (optional)

1. In the [Razorpay dashboard](https://dashboard.razorpay.com), switch to **Test Mode** →
   *Account & Settings* → *API Keys* → generate a key.
2. Create a file named `.env` next to `docker-compose.yml` (it's git-ignored) containing
   `RAZORPAY_KEY_ID=rzp_test_...` and `RAZORPAY_KEY_SECRET=...` on separate lines, then `docker compose up -d app`.
   (In IntelliJ, set the same values as environment variables in the run configuration instead.)
   Never commit these values or paste them anywhere public.
3. Open http://localhost:8090/razorpay-test.html, paste an access token, and pay with a
   [test card](https://razorpay.com/docs/payments/payments/test-card-details/) or the test UPI ID `success@razorpay`.
4. **Webhooks (optional):** Razorpay can't reach `localhost`, so expose port 8090 with a tunnel
   (e.g. `ngrok http 8090`), add a webhook in the dashboard pointing at `https://<tunnel>/api/v1/payments/webhook`
   with the events `payment.captured`, `payment.failed` and `order.paid`, and set `RAZORPAY_WEBHOOK_SECRET` to the
   secret you chose there. Without this, the verify call alone credits the wallet.

Without keys, `/api/v1/payments/*` returns `503 PAYMENTS_NOT_CONFIGURED`, and the instant simulated top-up
(`/api/v1/wallet/topup`) still works for demos. Turn it off in a real deployment with `SIMULATED_TOPUP_ENABLED=false`.

Admin endpoints (create sales, reconciliation) need an ADMIN token: log in as the bootstrap admin above.

## API (v1)

| Method | Endpoint | Auth | Notes |
|---|---|---|---|
| POST | `/api/v1/auth/signup` | – | Creates user + empty INR wallet, returns tokens |
| POST | `/api/v1/auth/login` | – | Returns access (15 min) + refresh (7 days) tokens |
| POST | `/api/v1/auth/refresh` | – | Exchange refresh token for new tokens (rejected after a reset, "log out everywhere" or deletion) |
| POST | `/api/v1/auth/verify-email` | – | `{token}` from the emailed link |
| POST | `/api/v1/auth/resend-verification` | User | Email a new verification link (at most one per minute) |
| POST | `/api/v1/auth/forgot-password` | – | `{email}`. Always `202`, whether or not the account exists |
| POST | `/api/v1/auth/reset-password` | – | `{token, newPassword}`. Logs out every device |
| GET | `/api/v1/users/me` | User | Profile, including `emailVerified` |
| POST | `/api/v1/users/me/logout-all` | User | Log out on every device |
| DELETE | `/api/v1/users/me` | User | `{password}`. Deletes the account (wallet must be empty) |
| GET | `/api/v1/health` | – | `{"status":"UP"}` for uptime monitors |
| GET | `/api/v1/wallet` | User | Balance |
| POST | `/api/v1/wallet/topup` | User | `Idempotency-Key` required. Instant simulated top-up (dev/demo only) |
| POST | `/api/v1/payments/topup-orders` | User | `Idempotency-Key` required. Creates a Razorpay order `{amount}` |
| POST | `/api/v1/payments/verify` | User | `{razorpayOrderId, razorpayPaymentId, razorpaySignature}` → credits wallet once |
| GET | `/api/v1/payments/orders/{orderId}` | User | Order status (`CREATED` / `PAID` / `FAILED`) |
| POST | `/api/v1/payments/webhook` | HMAC | Razorpay server-to-server. `X-Razorpay-Signature` header |
| POST | `/api/v1/wallet/transfer` | User | `Idempotency-Key` header required. `{recipientEmail, amount, note}` |
| GET | `/api/v1/wallet/transactions?page=0&size=20` | User | History, newest first |
| GET | `/api/v1/wallet/ledger?page=0&size=20` | User | Ledger rows with running balance |
| GET | `/api/v1/sales` | – | Upcoming and live sales with units left |
| GET | `/api/v1/sales/{id}` | – | One sale. Live stock: STOMP over WebSocket at `/ws`, topic `/topic/sales/{id}` |
| POST | `/api/v1/sales/{id}/reserve` | User | `202` + `holdToken` (status `PROCESSING`) / `409 SOLD_OUT` / `409 ALREADY_RESERVED` |
| GET | `/api/v1/sales/orders/{holdToken}` | User | `PROCESSING` → `HELD` → `PAID` (or `EXPIRED`) |
| POST | `/api/v1/sales/orders/{holdToken}/pay` | User | Pays from the wallet; safe to retry. `410 HOLD_EXPIRED` if too late |
| GET | `/api/v1/sales/orders` | User | My flash-sale orders |
| POST | `/api/v1/admin/sales` | Admin | `{name, price, totalUnits, startsAt, endsAt, holdSeconds}` |
| POST | `/api/v1/admin/sales/{id}/rebuild-inventory` | Admin | Rebuild the Redis pool from Postgres |
| GET | `/api/v1/admin/reconciliation` | Admin | Ledger vs balance check |
| GET | `/api/v1/notifications` | User | My notifications. Served by the **notification service** |

Rate limits (per minute): auth 10 per IP, money endpoints 20 per user, everything else 120 per user.
Every response carries `X-RateLimit-Limit` / `X-RateLimit-Remaining`; a `429` adds `Retry-After`.

Errors always use one JSON shape:
```json
{ "timestamp": "...", "status": 422, "code": "INSUFFICIENT_FUNDS", "message": "Insufficient wallet balance", "fieldErrors": {} }
```

### Quick try with curl
```bash
curl -X POST localhost:8090/api/v1/auth/signup -H "Content-Type: application/json" \
  -d '{"email":"a@demo.dev","fullName":"A","password":"password123"}'
# copy accessToken -> $T
curl -X POST localhost:8090/api/v1/wallet/topup -H "Authorization: Bearer $T" \
  -H "Idempotency-Key: 1" -H "Content-Type: application/json" -d '{"amount":1000}'
```

## Tests

63 tests, all passing: 57 in the core app, 3 in the notification service and 3 in the gateway, plus frontend lint and type-check.
Integration tests run against real Postgres, Redis and Kafka containers:

- `WalletConcurrencyTest`, against a real Postgres:
  - 50 parallel transfers of ₹30 from a ₹1000 balance → **exactly 33 succeed**, the balance ends at ₹10 and the ledger reconciles.
  - 40 opposing A↔B transfers → **no deadlocks**, and money is conserved.
  - 20 concurrent retries with the same key → **executed once**, 19 replays.
- `WalletFlowTest`: top-up, transfer, history, ledger, idempotent replay, key reuse, insufficient funds,
  validation, and the event contract (the parties travel with the event).
- `AuthFlowTest`: signup/login/refresh, token-type separation, 401/403 handling.
- `AccountFlowTest`: money is blocked until the email is verified and the link works once; forgot-password
  reveals nothing about unknown emails; a reset changes the password and kills old refresh *and* access tokens;
  "log out everywhere"; deletion anonymises the account, blocks login and transfers to it, and is refused
  while the wallet holds money; 5 wrong passwords lock the account; terms and plain names are required.
- `PaymentFlowTest` (Razorpay HTTP API mocked, real HMACs):
  - **10 verify calls + 10 webhooks fired at once → wallet credited exactly once.**
  - Idempotent order creation, forged signatures rejected, another user's order not confirmable,
    replayed webhooks ignored, an amount mismatch never credited, a failed attempt then a successful payment.
- `SaleFlowTest` (real Redis Lua, Kafka and Postgres):
  - **300 concurrent buyers, 50 units → exactly 50 granted, 250 sold out, 50 distinct units**, and 50 rows in the DB.
  - 20 concurrent reserves by one user → 1 granted. Reserve → pay from wallet (retry-safe).
    Insufficient funds keep the hold. An expired hold returns its unit and pay gets `410`. Sale window and
    ownership rules. Inventory rebuilt from the DB after Redis data loss.
- `notification-service` `NotificationFlowTest` (real Postgres + Kafka): an event published exactly as the
  core app sends it emails both parties once, and a redelivery sends nothing. Users see only their own
  notifications. Refresh, missing and forged tokens are rejected.
- `api-gateway` `GatewayRoutingTest`: routing to a fake backend, `X-Request-Id` added (and unsafe ones replaced),
  `X-Forwarded-For` passed on, and a dead service answered by the circuit breaker's JSON `503`.
- `RateLimitTest`: per-IP login limit with `429` + `Retry-After`, per-user money limit, webhook never limited.
- `JwtServiceTest`, `WalletTest`, `RazorpaySignaturesTest` (vectors from `openssl`), `RateLimiterTest`
  (fails open when Redis is down): unit tests.

## Load test

`load/flash-sale.js` (k6): 1,000 buyers fighting over 500 units, run in Docker on the same machine as the whole stack.
**~4,790 reserve requests/s, p95 103 ms, p99 144 ms, 0 errors, 500/500 units granted, zero oversell.**
Setup, caveats and how to reproduce: [load/RESULTS.md](load/RESULTS.md).

## Roadmap

- [x] **Phase 1:** Auth (JWT), wallet, double-entry ledger, locking, idempotency, outbox → Kafka, Testcontainers, CI
- [x] **Phase 2:** Razorpay test-mode top-ups (order + verified webhook), Redis + Bucket4j rate limiting, notification consumer (Kafka → email)
- [x] **Phase 3:** Flash-sale module: Redis Lua atomic stock, expiring holds, Kafka order queue, pay from wallet, WebSocket live stock, k6 load test (~4.8k req/s on one laptop, zero oversell)
- [x] **Phase 4:** React + Vite + Tailwind frontend, WebSocket live stock counter, served by nginx in Docker
- [x] **Phase 5:** Prometheus + Grafana dashboards, Zipkin tracing (across Kafka), notification service split out
  behind Spring Cloud Gateway, production compose override + [AWS deployment guide](DEPLOY.md)
- [x] **Production readiness:** email verification, password reset, session revocation, account deletion, legal
  pages, daily backups, uptime + Prometheus alerts, security review fixes

## Deploy

**Live demo for recruiters, free:** [deploy/ORACLE.md](deploy/ORACLE.md) puts it on an Oracle Cloud *Always Free*
Arm server with HTTPS in one script (`bash deploy/setup-server.sh`). Demo mode adds one-click demo logins with
play money and a flash sale that is always live.

For a real deployment, see **[DEPLOY.md](DEPLOY.md)**: a single EC2 instance with Docker Compose (with HTTPS via Caddy), or the managed-services
route (RDS, ElastiCache, MSK, SES, ECS Fargate). `docker-compose.prod.yml` refuses to start without real secrets,
publishes only the web port, turns off demo shortcuts, and sets `PRODUCTION=true` so the app refuses to start
with any of them. DEPLOY.md also covers backups (and restoring them), uptime alerts and the legal pages.
