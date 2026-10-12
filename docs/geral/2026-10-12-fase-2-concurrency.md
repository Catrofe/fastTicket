---
title: Fase 2 — Concorrência (estratégias de alocação de assentos e benchmark)
date: 2026-10-12
domain: geral
files:
  - build.gradle.kts
  - src/main/resources/application.yml
  - src/main/resources/db/changelog/phase-2/001-seat-version.sql
  - src/main/kotlin/com/fastticket/inventory/infra/allocator/
  - src/test/kotlin/com/fastticket/concurrency/
  - src/test/kotlin/com/fastticket/support/Race.kt
  - docs/adr/003-estrategia-padrao-de-alocacao-de-assentos.md
related-plan: .claude/plans/fase-2-concurrency-2026-10-12.md
---

## Contexto

A fase 1 tinha uma única forma de alocar assentos. A fase 2 precisava provar que a unicidade e o "tudo ou nada" dos pedidos multi-assento valem sob 100 threads, e escolher a estratégia padrão com base em medição.

## O que foi implementado

- **Três estratégias** atrás de `SeatAllocator`, escolhidas por `inventory.allocator.strategy` (padrão `conditional`): `ConditionalInsertSeatAllocator`, `PessimisticSeatAllocator` (`FOR NO KEY UPDATE NOWAIT`) e `OptimisticSeatAllocator` (compare-and-set em `seat_version`).
- **Migration** `phase-2/001-seat-version.sql`: tabela `seat_version`, dona `inventory`.
- **Suíte de corretude por estratégia** (`concurrency/`, parte do `./gradlew build`): 100 threads em um assento, pedidos multi-assento em ordem oposta e parcialmente sobrepostos, 100 pedidos sobrepostos aleatórios e 100 pedidos disjuntos. Verifica 1 vencedor, nenhuma alocação ou hold parcial, `ticket_count` coerente, nenhum 5xx e as 7 invariantes. Substitui o `ConcurrencySmokeIntegrationTest`.
- **Benchmark** (`./gradlew benchmark`, fora do `build`): 6 cenários (sem contenção, assento quente, multi-assento sobreposto, varredura de 20/100/1000 assentos), relatório em `build/reports/allocator-benchmark.md`.

## Como foi resolvido

- **Transação abortada:** um erro esperado (`55P03`) aborta a transação do Postgres, então o lock `NOWAIT` roda dentro de um savepoint (`PROPAGATION_NESTED`). O driver não traduz `55P03` para `CannotAcquireLockException`; o SQLSTATE é checado direto.
- **Otimista sem espera:** a semeadura cega de `seat_version` esperava escritores em andamento, o que bloqueava antes do CAS. Agora lê primeiro e semeia só as versões que faltam.
- **Pool:** os testes fixam o pool Hikari em 60 para 100 threads não medirem a fila do pool.
- **Decisão:** `conditional` permanece o padrão; `pessimistic` é a alternativa para contenção alta em multi-assento; `optimistic` não venceu em nenhum cenário. Tabela e justificativa no [ADR 003](../adr/003-estrategia-padrao-de-alocacao-de-assentos.md).
