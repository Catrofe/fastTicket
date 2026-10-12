# ADR 003 — Estratégia padrão de alocação de assentos (fase 2)

- **Status:** Proposto
- **Data:** 2026-10-12
- **Plano relacionado:** [2026-10-feat-plateia-architecture](../plans/2026-10-feat-plateia-architecture.md) (fase 2)
- **Complementa:** [ADR 001](001-monolito-modular-postgres-fonte-unica.md) e [ADR 002](002-fronteiras-de-modulo-e-persistencia.md) (pendência "estratégia de lock padrão")

## Contexto

A fase 1 entregou uma única forma de alocar assentos: `INSERT … ON CONFLICT DO NOTHING` contra o índice único parcial `ux_seat_active`. A fase 2 precisava provar que a garantia "nenhum assento vendido duas vezes, e pedidos multi-assento são tudo ou nada" vale independentemente da estratégia, medir o custo de cada uma e escolher o padrão.

A garantia não depende da estratégia: o índice `ux_seat_active` e o rollback da transação do `HoldService` (um `Conflict` vira `409 SEATS_TAKEN` e desfaz hold, itens, `buyer_event` e alocações já inseridas) continuam sendo a rede de segurança. As estratégias só mudam como a contenção é detectada e quanto ela custa.

## Decisão

1. **Três estratégias atrás da porta `SeatAllocator`**, escolhidas pela propriedade `inventory.allocator.strategy`:
   - `conditional` (padrão): `INSERT … ON CONFLICT DO NOTHING` por assento, em ordem crescente de id.
   - `pessimistic`: `SELECT … FROM seat WHERE id IN (…) ORDER BY id FOR NO KEY UPDATE NOWAIT`, depois checagem de alocações ativas e inserção. Falha de lock (`55P03`) vira `Conflict` dentro de um savepoint, porque um erro aborta a transação do Postgres. `NO KEY UPDATE` evita bloquear as checagens de chave estrangeira de outros inserts.
   - `optimistic`: compare-and-set na tabela nova `seat_version` (do `inventory`): lê versões, checa alocações ativas e faz `UPDATE … SET version = version + 1 WHERE seat_id = ? AND version = ?` para cada assento, em ordem; 0 linhas atualizadas é `Conflict`. As versões são semeadas sob demanda, só para assentos ainda sem linha, para que o caminho quente não espere escritores em andamento.
2. **Padrão: `conditional`.** Ver benchmark abaixo. É a mais simples, não trava linhas do `catalog` e não precisa de tabela extra; perde para a pessimista só em multi-assento sob contenção alta.
3. **`pessimistic` é a alternativa recomendada** se a abertura de vendas mostrar contenção alta em multi-assento; a troca é só configuração. **`optimistic` não é recomendada**: não venceu em nenhum cenário e custa uma tabela e mais instruções por pedido. Fica como candidata a remoção.
4. **Nova tabela `seat_version`** (migration `phase-2/001-seat-version.sql`), dona: `inventory`. Coluna em `seat` foi rejeitada porque `seat` é do `catalog`.

## Benchmark

100 threads por rodada, 1 rodada de aquecimento descartada e 5 medidas (mediana), `postgres:17` em Testcontainers, pool Hikari fixo em 60, mesma máquina de desenvolvimento para as três estratégias. Chamadas via MockMvc (sem pilha HTTP), portanto os números comparam estratégias entre si e **não** são comparáveis aos do k6 da fase 5. Reproduzir com `./gradlew benchmark` (relatório em `build/reports/allocator-benchmark.md`). Segunda de duas rodadas; o padrão se repetiu nas duas.

| Cenário | Conflito % | p99 ms (cond / pess / otim) | Holds/s (cond / pess / otim) |
|---|---:|---|---|
| A. sem contenção (100 assentos disjuntos) | 0 | **50** / 64 / 66 | **1763** / 1489 / 1445 |
| B. um assento quente | 99 | **32** / 42 / 42 | **28** / 23 / 22 |
| C. multi-assento sobreposto (2–4 de 6) | 97 | 79 / **46** / 75 | 37 / **59** / 40 |
| D1. varredura, 20 assentos | 85–86 | 51 / **42** / 53 | 249 / **298** / 264 |
| D2. varredura, 100 assentos | 65–67 | 43 / **41** / 50 | 712 / **792** / 668 |
| D3. varredura, 1000 assentos | 20–22 | **42** / 52 / 55 | **1635** / 1434 / 1303 |

0 erros (nenhum 5xx) e 0 duplicatas em todas as estratégias e cenários; as 7 invariantes passam após cada teste.

Leitura: sem contenção ou com contenção baixa, a condicional é a mais rápida (menos instruções). Com muitos pedidos multi-assento disputando poucos assentos, a pessimista rejeita de imediato (`NOWAIT`) em vez de esperar o commit do concorrente, e ganha em p99 e vazão. A taxa de conflito é a mesma nas três (ela depende da carga, não da estratégia).

## Alternativas consideradas

- **Coluna `version` em `seat`:** rejeitada, escrita em tabela do `catalog`.
- **Laço de retry no CAS perdido:** rejeitada; o assento específico foi perdido, repetir só aumenta a carga.
- **`SERIALIZABLE`:** rejeitada, gera tempestade de retries e esconde a comparação.
- **Travar linhas de `seat_version` em vez de `seat` na pessimista:** adiada; exigiria a mesma semeadura e o ganho não foi medido.

## Consequências

- **Positivas:** a garantia de unicidade é provada em 100 threads para as três estratégias (suíte `concurrency/`, parte do `./gradlew build`); trocar a estratégia não exige mudança de código; o benchmark é reproduzível.
- **Negativas:** a estratégia pessimista trava linhas de `seat` (do `catalog`) a partir de SQL do `inventory`, algo que o ArchUnit não enxerga; a otimista cria uma tabela só para o benchmark enquanto não for removida; os números vêm de uma máquina de desenvolvimento e de MockMvc.
- **Riscos:** o `NOWAIT` pode rejeitar um pedido que teria sucesso se o concorrente fosse desfeito logo em seguida (o cliente refaz o pedido); a semeadura inicial de `seat_version` dentro da transação do hold serializa a primeira reserva de cada assento.
- **Pendente:** reavaliar o padrão com a fila virtual da fase 5 ligada; decidir sobre a remoção da estratégia otimista.
