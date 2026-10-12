# fastTicket (Plateia)

Seat-reserved ticketing API: pick seats on a map, hold them for 10 minutes while paying, and never sell a seat twice.

Modular monolith in Kotlin + Spring Boot, with Postgres as the single source of truth (and the only clock) and Redis only where Postgres would be a bottleneck (virtual queue, SSE fan-out, cache, rate limit).

## Status

Phase 0 (Foundation) done: build, module skeleton with ArchUnit-enforced boundaries, database clock (`DbClock`), `api`/`worker` profiles, invariant checker and local stack. Phase 1 (Core) implemented: schema, event registration, seatmap, hold with expiration, synchronous card checkout (persistence with `JdbcClient`; see ADR 002). Phase 2 (Concurrency) implemented: three `SeatAllocator` strategies (`conditional`, `pessimistic`, `optimistic`) proven with 100-thread tests and benchmarked; default chosen in ADR 003. See `docs/plans/` and `docs/adr/`.

## Run locally

```
cp infra/.env.example infra/.env   # set the passwords
docker compose -f infra/docker-compose.yml --env-file infra/.env up --build
```

Starts `postgres`, `gateway-mock` (WireMock) and `api` (http://localhost:8080, health at `/actuator/health`). The passwords (`POSTGRES_PASSWORD`, `DB_ADMIN_PASSWORD`, `DB_PASSWORD`) are required (no default). Liquibase runs as `DB_ADMIN_USER` (schema owner, DDL); the application connects as `DB_USER`, which only has DML (SELECT/INSERT/UPDATE/DELETE, sequence USAGE). Roles are created on first init of the volume (`infra/postgres/init-roles.*`); after changing them run `docker compose ... down -v`.

## Roteiro de compra manual (curl)

Com o compose no ar (`jq` é usado para extrair ids). `INVENTORY_HOLD_TTL` (padrão `10m`) define o TTL do hold; use `20s` para testar a expiração.

```
A=localhost:8080; H='Content-Type: application/json'; CPF=52998224725

curl -s -XPOST $A/events -H "$H" -d '{"name":"Show","startsAt":"2027-01-01T20:00:00Z","salesOpenAt":"2026-01-01T10:00:00Z","sectors":[{"name":"Pista","kind":"NUMBERED","basePrice":150.50,"rows":[{"label":"A","seats":10}]}]}' > /tmp/ev.json
EV=$(jq -r .id /tmp/ev.json)

curl -s $A/events/$EV/seatmap > /tmp/sm.json
SEAT=$(jq -r '.sectors[0].rows[0].seats[0].id' /tmp/sm.json)

HOLD=$(curl -s -XPOST $A/holds -H "$H" -H 'Idempotency-Key: hold-1' -H "X-Buyer-Cpf: $CPF" \
  -d "{\"event_id\":\"$EV\",\"seat_ids\":[\"$SEAT\"]}" | jq -r .hold_id)

curl -s -XPOST $A/holds/$HOLD/checkout -H "$H" -H 'Idempotency-Key: pay-1' -H "X-Buyer-Cpf: $CPF" \
  -d '{"method":"CREDIT","token":"tok_ok"}'                       # 201 {"order_id":...}

curl -s $A/holds/$HOLD/checkout -H "X-Buyer-Cpf: $CPF"             # hold + pagamento + pedido
```

Repetir o checkout com a mesma `Idempotency-Key` devolve a mesma resposta (sem nova cobrança). Com `"token":"tok_declined"` a resposta é `402 PAYMENT_DECLINED` e o hold continua ativo para nova tentativa (nova `Idempotency-Key`). Outros tokens do mock: `tok_5xx` e `tok_timeout` (resultam em `202`, pagamento `PENDING`).

Um hold abandonado volta a `AVAILABLE` no seatmap em até ~5 s após `expires_at` (intervalo do job de expiração).

Derrubar: `docker compose -f infra/docker-compose.yml --env-file infra/.env down -v`.

## Tests

```
./gradlew build
```

Requires Docker (Testcontainers starts Postgres).

Seat allocation strategy: `inventory.allocator.strategy` = `conditional` (default) | `pessimistic` | `optimistic`. Compare them with `./gradlew benchmark` (report in `build/reports/allocator-benchmark.md`; not part of `build`).

## Profiles

One image, two roles: `api` (HTTP) and `worker` (jobs). `combined` (default) activates both and is what runs until phase 5.
