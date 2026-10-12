---
feature: Plateia — domains, folder architecture and phasing
date: 2026-10-12
status: proposed
domains:
  - catalog
  - pricing
  - inventory
  - payments
  - checkout
  - queue
  - realtime
  - jobs
versioning-criteria:
  - Introduces new domains/modules
  - Adds external integration (payment gateway, Redis)
  - Business-critical feature (payments, inventory consistency)
  - Significant architectural decisions
---

# Implementation Plan: Plateia (fastTicket) — Domains, Folder Architecture, Phasing

> Sources: the Plateia ticketing PRD and the Plateia architecture document (kept outside the repo, not versioned). Where this plan differs from them (synchronous payments, no webhook, no outbox, expiration in phase 1), this plan and the ADR prevail.
> Language: code, identifiers and table names in English. Documentation in pt-BR and English.
> Decision record: [ADR 001](../adr/001-monolito-modular-postgres-fonte-unica.md).

## Overview

Plateia is a seat-reserved ticketing API: hold seats for 10 minutes, pay with an idempotent checkout, never sell a seat twice. Architecture is a **modular monolith** (single Gradle module, base package `com.fastticket`), one image running as `api` or `worker` profile. Postgres is the single source of truth and the only clock; Redis is used only from phase 5. Payments are **synchronous, card only** (credit and debit online); there is no webhook.

## Decisions (confirmed)

- Base package stays `com.fastticket`; single Gradle module; package-by-module with light layers (`api`, `application`, `domain`, `infra`).
- Payment gateway mock: **WireMock** with chaos, as a separate container in docker-compose.
- **Synchronous payments, no webhook, no outbox.** Only `CREDIT` and `DEBIT`; Pix and boleto are out of scope.
- Remove the `org.graalvm.buildtools.native` plugin.
- Code and names in English; docs in pt-BR and English.

## 1. Domains

| Module | Owns | Depends on |
|---|---|---|
| `catalog` | event, sector, seat, seatmap (read) | `shared/*` |
| `pricing` | lot, half-price quota (table `sector_half_quota`: `half_cap/half_held/half_sold`), current-lot resolution | `catalog`, `shared/*` |
| `inventory` | seat_allocation, sector_inventory, buyer_event, hold, hold_item, `SeatAllocator` | `catalog`, `pricing`, `shared/*` (incl. idempotency) |
| `payments` | `PaymentGateway` port (synchronous), adapters (WireMock/real) | `shared/*` |
| `checkout` | payment, order, refund, cancellation | `inventory`, `payments`, `shared/*` |
| `queue` | virtual queue (Redis), admission, JWT access token | `shared/*` |
| `realtime` | SSE, pub/sub, seatmap cache and version | listens to events from `inventory`, `checkout` |
| `jobs` | hold expiration sweeper, payment reconciliation, queue admission | public APIs of other modules |

Rules:
- `shared/*` (idempotency, error handling, `DbClock`, config) can be used by every module.
- Idempotency table lives in `shared/idempotency` (used by holds and checkout).
- Hold + allocation stay in `inventory`: one transaction must touch seat, lot, half quota and `buyer_event`. Lock order is always `buyer_event` → `hold` → `seat_allocation`/counters. `buyer_event.ticket_count` counts tickets in active holds plus confirmed orders; there is no `active_hold_id` (the partial unique index `ux_one_active_hold` decides).
- Persistence is `JdbcClient` (no JPA). The package root of a module is its public API; `application` never depends on `infra`/`api`; `jobs` stays thin. See [ADR 002](../adr/002-fronteiras-de-modulo-e-persistencia.md).
- `pricing` exposes operations that join the caller's transaction (`reserveLot`, `reserveHalf`, `releaseLot`, `commitLot`).
- `realtime` is triggered by application events after commit (`@TransactionalEventListener(AFTER_COMMIT)`); business modules never know Redis.
- Module dependency rules enforced by an ArchUnit test (from phase 0).

### Time

`DbClock` is the only time source and it is the **database** clock. `now()` is frozen at transaction start, so `hold.expires_at` and every deadline decision taken after a lock wait (checkout validation, expiration, cancellation window) use `clock_timestamp()`, evaluated after the locks are acquired.

### Payments (synchronous, card only)

- `payment.method` is `CREDIT | DEBIT`. The API never receives or stores card numbers: the client sends a gateway token (the mock accepts a fixed token).
- Gateway contract: `POST /charges {reference, amount, token}`, `GET /charges/{reference}`, `POST /refunds {reference, payment_reference, amount}`; all synchronous and idempotent by `reference` (`payment.id` / `refund.id`).
- Checkout never calls the gateway inside a database transaction:
  1. **tx1:** validate the hold (deadline by `clock_timestamp()`), create `payment PENDING`, commit.
  2. **Call the gateway** (no transaction open, bounded timeout).
  3. **tx2:** apply the result.
- Three outcomes, not two:
  - `APPROVED` → confirm (allocations `HELD → SOLD`, counters `held → sold`, `order CONFIRMED`).
  - `DECLINED` (gateway answered) → `payment DECLINED`; the hold stays active for another attempt until it expires.
  - `UNKNOWN` (timeout or 5xx; the charge may have happened) → `payment` stays `PENDING`; the API answers `202`. It is **never** treated as declined.
- A hold with an in-flight `PENDING` payment is **not** expired by the sweeper, up to a bounded grace window.
- If the approval arrives after the hold expired: honor it when the same items can be allocated again with the same conditional updates; otherwise create a `refund` (reason `EXPIRED_AFTER_APPROVAL`).
- `PaymentReconciliationJob` resolves `PENDING` payments (and refunds) older than a threshold with `GET /charges/{reference}` or an idempotent retry with the same `reference`, then runs the same result handler. Claiming rows uses `FOR UPDATE SKIP LOCKED`, so replicas running the job never process the same row twice, and the idempotent `reference` guarantees the buyer is not charged twice even if a call is repeated.

### Seatmap freshness

Phase 1 has no `seatmap_version`: the seatmap is read straight from the database. From phase 5, a version is incremented **after** the commit that changes availability (e.g. Redis `INCR`), never inside the hold transaction, because an `UPDATE` on the event row would serialize every hold of the same event (see [ADR 002](../adr/002-fronteiras-de-modulo-e-persistencia.md)). Published events and the cache carry this version; clients refetch on a version gap. The snapshot cache has a short TTL (2–5 s) as a safety net, so a lost invalidation (crash after commit, failed publish) heals on its own without a durable event outbox.

## 2. Folder Architecture

```
src/main/kotlin/com/fastticket/
├── FastTicketApplication.kt
├── shared/
│   ├── config/        # profiles (api|worker|combined), Jackson
│   ├── idempotency/
│   ├── error/         # ProblemDetail handler
│   └── time/          # DbClock (database clock_timestamp()) — only time source
├── catalog/   {api, application, domain, infra}
├── pricing/   {application, domain, infra}
├── inventory/ {api, application, domain, infra/{allocator,persistence}}
├── payments/  PaymentGateway.kt, infra/ (WireMock adapter, real adapter later)
├── checkout/  {api, application, domain, infra}
├── queue/     {api, application, infra}
├── realtime/  {api, infra}
└── jobs/      HoldExpirationJob, PaymentReconciliationJob, QueueAdmissionJob

src/main/resources/
├── application.yml, application-api.yml, application-worker.yml
└── db/changelog/  (master + one file per phase)

src/test/kotlin/com/fastticket/
├── support/       # Postgres/Redis containers, InvariantChecker (5 invariant queries), builders
├── architecture/  # ModuleDependencyTest (ArchUnit)
├── <module>/...
└── concurrency/   # single-seat and multi-seat contention, gateway chaos

infra/   docker-compose.yml, wiremock/ (mappings + chaos), k6/, grafana/
docs/    plans/, adr/, features/ (generated by document)
```

## 3. Phases

Phase 0 is preparation with a technical readiness gate and is **not** one of the product's five delivery phases. Phases 1 to 5 are the five delivery phases. Each phase starts only when the previous one passes its gate.

| Phase | Deliverable | Gate |
|---|---|---|
| **0. Foundation** (preparation) | Deps (jdbc, micrometer-prometheus, ArchUnit), remove GraalVM plugin, docker-compose (api + postgres + wiremock), Liquibase base, `DbClock`, profiles, `InvariantChecker` | App boots in compose; `InvariantChecker` runs on empty DB; ArchUnit green |
| **1. Core** | Full schema with constraints; event/sector/seat registration; seatmap; hold with conditional insert + idempotency; **hold expiration (sweeper job + lazy expiration at checkout)**; synchronous card checkout (tx1 / gateway / tx2, `APPROVED` / `DECLINED` / `UNKNOWN`) | Simple purchase end to end; repeated `Idempotency-Key` returns the same response; an abandoned hold frees its seats in < 30 s; 5 invariants green after tests |
| **2. Concurrency** | `SeatAllocator` with 3 strategies (conditional, pessimistic `NOWAIT`, optimistic) + benchmark | 100 threads on one seat → 1 winner; **concurrent multi-seat requests with overlapping seats → each request fully succeeds or leaves no partial allocation**; 0 duplicates in all 3; comparison table (conflict rate, p99); default chosen via ADR |
| **3. Resilience** | WireMock chaos (`LATENCY_MS`, `TIMEOUT_BEFORE_PCT`, `TIMEOUT_AFTER_PCT` = charged but response lost, `DECLINE_PCT`), `PaymentReconciliationJob`, late approval (honor or refund), cancellation + refund | Chaos test with 0 orphan payments; retry with the same `reference` never charges twice; sweeper skips holds with in-flight payment; late approval honored or refunded; `UNKNOWN` is never treated as declined |
| **4. Pricing and seat rules** | GA lots, half price (40%), isolated-seat rule, 6-per-CPF limit | One test per PRD rule; invariants 2, 3, 4 under concurrent load |
| **5. Scale** | Redis: virtual queue, SSE pub/sub, seatmap cache with `version` + short TTL, CPF rate limit; separate `worker` container; metrics + Grafana | k6 with 5k VUs and queue on: p99 `POST /holds` < 300 ms; SSE delay < 2 s; 0 duplicates, 0 orphans. An additional 10k VU run is **informative** (no pass/fail), since local load does not represent production |

Phase 1 ships the whole schema with constraints (including `lot`, `sector_inventory`, `refund`) because it is the costliest thing to change later; lot/half *business rules* only arrive in phase 4. Hold expiration lives in phase 1 because, without it, abandoned holds would keep seats locked and contaminate the phase 2 benchmark.

## Verification Command

`./gradlew build`

## Testing Strategy

- [ ] ArchUnit module-dependency test
- [ ] Testcontainers (Postgres, later Redis) with the 5 invariants as teardown queries
- [ ] Concurrency tests (100 threads on one seat; overlapping multi-seat requests)
- [ ] Gateway chaos test (WireMock), including `UNKNOWN` outcomes and repeated calls with the same `reference`
- [ ] k6 load test (phase 5)

## Security Considerations

- **Sensitive areas:** PII (CPF), payments (card), external API (gateway), user input, database migrations.
- **Mitigations:**
  - [ ] The API never receives or stores card numbers; only gateway tokens
  - [ ] Never log full CPF (mask) or store it unprotected beyond what the model needs
  - [ ] Validate and bound all inputs (qty, ids); parameterized queries only
  - [ ] Gateway credentials and the queue JWT secret come from the environment, never hardcoded
  - [ ] Migrations: forward-only, reviewed for locks (see safe-migrations)

## Considerations

- **Risk:** scope creep before phase 2 is solid; Spring Boot 4.1.1 compatibility of Redis/Micrometer starters (check before phase 5); local load numbers compare strategies, not production; real online debit often requires an asynchronous bank authentication step, so the real adapter may reintroduce a callback. The `PaymentGateway` port keeps `PENDING` as a valid result for that reason.
- **Alternative:** multi-module Gradle per domain (rejected: unnecessary at this size); webhook plus outbox for payments (rejected: unnecessary for synchronous card payments).
- **Out of scope:** Pix and boleto, real gateway, resale/transfer, gate access/QR, full frontend, "best seats" feature (removed from architecture).

## Delivery Workflow

> Applies to every phase. Each phase is a separate cycle: plan → implement → review loop → document → PR.

1. [ ] **Plan** — `/craft-core:plan` for the phase (this document is the master plan; each phase gets its own detailed plan)
2. [ ] **Create branch** `feat/<phase-slug>` (e.g. `feat/foundation`, `feat/core-hold-checkout`)
3. [ ] **Implement** — `/craft-core:implement` executes the phase plan
4. [ ] **Review loop** — `/craft-core:review`, fix findings with `/craft-core:implement`, repeat until confident (no open blocking findings, `./gradlew build` green)
5. [ ] **Document** — `/craft-core:document` generates what was done and how (per domain, in `docs/`), pt-BR and English
6. [ ] **Commit** — Conventional Commits, **no `Co-Authored-By` and no AI attribution**
7. [ ] **Open pull request in draft** — `gh pr create --draft` (add `--label "migration"` on phases with schema changes: phase 1 onward)
