---
title: Fase 0 — Foundation
date: 2026-10-12
domain: geral
files:
  - build.gradle.kts
  - Dockerfile
  - infra/docker-compose.yml
  - src/main/resources/application.yml
  - src/main/kotlin/com/fastticket/shared/time/DbClock.kt
  - src/main/kotlin/com/fastticket/shared/config/Profiles.kt
  - src/main/kotlin/com/fastticket/payments/PaymentGateway.kt
  - src/test/kotlin/com/fastticket/architecture/ModuleDependencyTest.kt
  - src/test/kotlin/com/fastticket/support/InvariantChecker.kt
related-plan: docs/plans/2026-10-feat-plateia-architecture.md
---

## Contexto

O repositório era só o esqueleto do Spring Initializr. Antes de escrever regra de negócio (fase 1), era preciso um build reprodutível, uma stack local, fronteiras de módulo verificadas e as bases que as fases seguintes assumem: relógio do banco, perfis `api`/`worker` e verificação das invariantes.

## O que foi implementado

- **Build e config:** plugin GraalVM removido, actuator e registry Prometheus adicionados, `application.properties` trocado por `application*.yml` e changelog Liquibase vazio. O perfil padrão é `combined` (grupo `api` + `worker`).
- **`DbClock`:** única fonte de tempo, baseada em `clock_timestamp()` do Postgres.
- **Porta `PaymentGateway`:** só o contrato, com os resultados `Approved`, `Declined` e `Unknown`, para a fase 1 e a fase 3 partirem do mesmo desenho.
- **Stack local:** `Dockerfile` multi-stage (runtime não-root) e `infra/docker-compose.yml` com `api`, `postgres` e `gateway-mock` (WireMock, stubs do caminho feliz).
- **Testes de suporte:** Testcontainers com Postgres 17 fixo, `InvariantChecker` (5 invariantes) e `InvariantsExtension` para uso como teardown.
- **ArchUnit:** matriz de dependência entre módulos, ausência de ciclos, camadas internas, uso exclusivo do `DbClock` para tempo e Redis restrito a `queue`, `realtime` e `jobs`.

## Como foi resolvido

- As regras do ArchUnit usam `allowEmptyShould(true)`, porque os módulos ainda estão vazios. Para uma regra com pacote errado não passar em silêncio, um teste importa fixtures que violam cada regra e exige que ela falhe.
- O schema só chega na fase 1, então cada invariante é protegida por `to_regclass`: nenhuma tabela presente devolve `SKIPPED`, todas presentes executa a query (com tabelas qualificadas pelo schema) e presença parcial falha com `VIOLATED`, para não esconder violação. Um teste cria tabelas de rascunho numa transação (revertida) e confirma que cada query detecta sua violação. Esses DDLs precisam ser reconciliados com o schema real na fase 1.
- `DbClock` usa `clock_timestamp()` e não `now()`, que fica fixo no início da transação (ADR 001).
- Não há senha padrão em lugar nenhum: nem `DB_PASSWORD` no `application.yml` nem `POSTGRES_PASSWORD` no compose. Sem as variáveis a aplicação e o compose falham ao subir, em vez de usar uma credencial previsível. Os testes não são afetados, porque o `@ServiceConnection` do Testcontainers fornece as credenciais. O compose lê a senha de `infra/.env`.
- O endpoint Prometheus e o Swagger ficam abertos no perfil `api`. É aceitável enquanto não há deploy nem autenticação, e precisa ser restringido antes de expor a aplicação.
- Adiados de propósito: restringir Prometheus e Swagger (depende de deploy e autenticação), isolar o `worker` com health e métricas próprios (fase 5, quando vira container separado) e usar uma role de aplicação sem superusuário (fase 1, junto do schema).
- O ArchUnit também falha para pacotes fora de um módulo conhecido e para `java.util.Date`/`Calendar` fora de `shared.time`. Cada regra tem uma fixture que a faz falhar.
