# fastTicket (Plateia)

Seat-reserved ticketing API: pick seats on a map, hold them for 10 minutes while paying, and never sell a seat twice.

Modular monolith in Kotlin + Spring Boot, with Postgres as the single source of truth (and the only clock) and Redis only where Postgres would be a bottleneck (virtual queue, SSE fan-out, cache, rate limit).

## Status

Project skeleton. Documentation and the phased roadmap land in follow-up PRs.
