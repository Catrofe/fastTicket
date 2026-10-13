# ADR 003 — Estratégia padrão de alocação de assentos (fase 2)

- **Status:** Proposto
- **Data:** 2026-10-12
- **Plano relacionado:** [2026-10-feat-plateia-architecture](../plans/2026-10-feat-plateia-architecture.md) (fase 2)
- **Complementa:** [ADR 001](001-monolito-modular-postgres-fonte-unica.md) e [ADR 002](002-fronteiras-de-modulo-e-persistencia.md) (pendência "estratégia de lock padrão")

## Contexto

A fase 1 entregou uma única forma de alocar assentos: `INSERT … ON CONFLICT DO NOTHING` contra o índice único parcial `ux_seat_active`. A fase 2 precisava provar que a garantia "nenhum assento vendido duas vezes, e pedidos multi-assento são tudo ou nada" vale independentemente da estratégia, medir o custo de cada uma e escolher o padrão.

A garantia não depende da estratégia: o índice `ux_seat_active` e o rollback da transação do `HoldService` (um `Conflict` vira `409 SEATS_TAKEN` e desfaz hold, itens, `buyer_event` e alocações já inseridas) continuam sendo a rede de segurança. As estratégias só mudam como a contenção é detectada e quanto ela custa.

## Decisão

1. **Duas estratégias atrás da porta `SeatAllocator`**, escolhidas pela propriedade `inventory.allocator.strategy`:
   - `conditional` (padrão): `INSERT … ON CONFLICT DO NOTHING` por assento, em ordem crescente de id.
   - `pessimistic`: `SELECT … FROM seat WHERE id IN (…) ORDER BY id FOR NO KEY UPDATE NOWAIT`, depois checagem de alocações ativas e inserção. Falha de lock (`55P03`) vira `Conflict` dentro de um savepoint, porque um erro aborta a transação do Postgres. `NO KEY UPDATE` evita bloquear as checagens de chave estrangeira de outros inserts.
2. **Padrão: `conditional`.** Ver benchmark abaixo. É a mais simples, não trava linhas do `catalog` e não precisa de tabela extra; perde para a pessimista apenas em p99 de multi-assento sob contenção alta.
3. **`pessimistic` é a alternativa recomendada** se a abertura de vendas mostrar contenção alta em multi-assento; a troca é só configuração.
4. **Estratégia otimista removida.** Foi implementada (compare-and-set numa tabela `seat_version`) e medida junto com as outras, mas não venceu em nenhum cenário e custava uma tabela nova, uma migration e mais instruções por pedido. O código, a migration e o benchmark dela foram removidos antes do merge e não aparecem na tabela abaixo (a medição dela foi feita com o pool anterior, de 60 conexões).

## Benchmark

100 threads por rodada, 1 rodada de aquecimento descartada e 5 medidas (mediana), `postgres:17` em Testcontainers com `max_connections=200`, pool Hikari fixo em 100 (uma conexão por thread, para o pool não enfileirar e contaminar latência e vazão), mesma máquina de desenvolvimento para as duas estratégias. Percentis por nearest-rank sobre as 100 requisições da rodada. Chamadas via MockMvc (sem pilha HTTP), portanto os números comparam estratégias entre si e **não** são comparáveis aos do k6 da fase 5. Reproduzir com `./gradlew benchmark` (relatório em `build/reports/allocator-benchmark.md`).

| Cenário | Conflito % | p99 ms (cond / pess) | Holds/s (cond / pess) |
|---|---:|---|---|
| A. sem contenção (100 assentos disjuntos) | 0 | **54** / 66 | **1615** / 1434 |
| B. um assento quente | 99 | **34** / 40 | **28** / 24 |
| C. multi-assento sobreposto (2–4 de 6) | 97–98 | 59 / **45** | 50 / 50 |
| D1. varredura, 20 assentos | 85–86 | 43 / **37** | 347 / **361** |
| D2. varredura, 100 assentos | 63–64 | **40** / 45 | **866** / 724 |
| D3. varredura, 1000 assentos | 20–21 | **43** / 52 | **1606** / 1407 |

0 erros (nenhum 5xx) e 0 duplicatas em todas as estratégias e cenários; as 7 invariantes passam após cada teste.

Leitura: a condicional é a mais rápida sem contenção, com contenção baixa e no assento quente (menos instruções por pedido). A pessimista só se destaca em p99 quando muitos pedidos multi-assento disputam poucos assentos (C: 45 contra 59 ms; D1: 37 contra 43 ms), porque rejeita de imediato (`NOWAIT`) em vez de esperar o commit do concorrente; mesmo ali a vazão empata. A taxa de conflito é a mesma nas duas (ela depende da carga, não da estratégia). Uma medição anterior, com o pool em 60 para 100 threads, mostrava vantagem bem maior da pessimista, porque a rejeição rápida devolvia a conexão ao pool mais cedo; com o pool dimensionado essa vantagem quase desaparece, o que reforça manter a condicional.

## Alternativas consideradas

- **Estratégia otimista (compare-and-set em tabela de versão):** implementada, medida e descartada (decisão 4).
- **`SERIALIZABLE`:** rejeitada, gera tempestade de retries e esconde a comparação.
- **Travar uma tabela própria do `inventory` em vez de `seat` na pessimista:** adiada; exigiria uma tabela e semeadura novas e o ganho não foi medido.

## Consequências

- **Positivas:** a garantia de unicidade é provada em 100 threads para as duas estratégias restantes (suíte `concurrency/`, parte do `./gradlew build`); trocar a estratégia não exige mudança de código; o benchmark é reproduzível.
- **Negativas:** a estratégia pessimista trava linhas de `seat` (do `catalog`) a partir de SQL do `inventory`, algo que o ArchUnit não enxerga; os números vêm de uma máquina de desenvolvimento e de MockMvc.
- **Riscos:** o `NOWAIT` pode rejeitar um pedido que teria sucesso se o concorrente fosse desfeito logo em seguida (o cliente refaz o pedido).
- **Pendente:** reavaliar o padrão com a fila virtual da fase 5 ligada.
