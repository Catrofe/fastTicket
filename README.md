# fastTicket (Plateia)

Seat-reserved ticketing API: pick seats on a map, hold them for 10 minutes while paying, and never sell a seat twice.

Modular monolith in Kotlin + Spring Boot, with Postgres as the single source of truth (and the only clock) and Redis only where Postgres would be a bottleneck (virtual queue, SSE fan-out, cache, rate limit).

## Status

Phase 0 (Foundation) done: build, module skeleton with ArchUnit-enforced boundaries, database clock (`DbClock`), `api`/`worker` profiles, invariant checker and local stack. Business features start in phase 1. See `docs/plans/` and `docs/adr/`.

## Run locally

```
docker compose -f infra/docker-compose.yml up --build
```

Starts `postgres`, `gateway-mock` (WireMock) and `api` (http://localhost:8080, health at `/actuator/health`). Copy `infra/.env.example` to `infra/.env` to override the local credentials.

## Tests

```
./gradlew build
```

Requires Docker (Testcontainers starts Postgres).

## Profiles

One image, two roles: `api` (HTTP) and `worker` (jobs). `combined` (default) activates both and is what runs until phase 5.
