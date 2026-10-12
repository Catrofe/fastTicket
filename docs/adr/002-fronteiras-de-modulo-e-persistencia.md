# ADR 002 — Fronteiras de módulo, persistência com JdbcClient e ajustes de schema da fase 1

- **Status:** Proposto
- **Data:** 2026-10-12
- **Plano relacionado:** [2026-10-feat-plateia-architecture](../plans/2026-10-feat-plateia-architecture.md)
- **Complementa:** [ADR 001](001-monolito-modular-postgres-fonte-unica.md) (emenda ao item 4)

## Contexto

Ao detalhar a fase 1 (núcleo: cadastro, seatmap, hold, expiração e checkout), a revisão do plano mestre encontrou lacunas nas fronteiras entre módulos, na escolha de persistência e em pontos do schema que causariam contenção ou ciclos de dependência. Este ADR registra as decisões tomadas (identificadas como A1 a A10 no plano da fase) e os desvios assumidos durante a implementação.

## Decisão

1. **JdbcClient em vez de JPA (A3).** O caminho crítico usa SQL condicional (`INSERT … ON CONFLICT`, `UPDATE` com guarda, `FOR UPDATE SKIP LOCKED`), e o `DbClock` já usa `JdbcClient`. Misturar JPA e JDBC na mesma transação traz problemas de flush e de visibilidade. O pacote `infra/jpa` vira `infra/persistence`, e o JPA sai do build. As portas de repositório ficam em `domain`; as implementações JDBC, em `infra`.
2. **Pacote raiz do módulo é a API pública; regras ArchUnit A1, A2 e A6.**
   - **A1:** só `com.fastticket.<modulo>` (fachadas, DTOs de leitura, SPIs) é visível a outros módulos. `api`, `application`, `domain` e `infra` são internos. Há regra ArchUnit com fixture que a viola.
   - **A2:** `application` não depende de `infra` nem de `api`; depende só de portas em `domain`. Isso mantém o `SeatAllocator` e os repositórios trocáveis.
   - **A6:** `jobs` é fino: agenda, faz o laço de lote e chama a API pública do módulo dono (`InventoryFacade`). Sem `domain`, sem `infra`, sem SQL.
3. **`seatmap_version` fora da transação do hold, adiado para a fase 5 (A9).** Emenda ao item 4 do ADR 001. Um `UPDATE event SET seatmap_version = seatmap_version + 1` na transação do hold mantém o lock da linha do evento até o commit e serializa todos os holds do mesmo evento, o que invalidaria o benchmark das estratégias de lock (fase 2) e o p99 da fase 5. A coluna não existe no schema da fase 1 e o seatmap é lido direto do banco. Na fase 5 a versão será incrementada **depois do commit** (por exemplo `INCR` no Redis); o TTL curto do cache cobre a janela da corrida.
4. **Tabela `sector_half_quota` do `pricing` (A4).** A cota de meia (`half_cap`, `half_held`, `half_sold`, com CHECK) sai de `sector` (do `catalog`) para tabela própria, para que dois módulos não escrevam na mesma tabela. O `InvariantChecker` (invariante 3) foi ajustado.
5. **Remoção de `buyer_event.active_hold_id` e nova semântica de `ticket_count` (A5).** A coluna era redundante com o índice único parcial `ux_one_active_hold (buyer_cpf, event_id) WHERE status = 'ACTIVE'` e forçaria o confirm a travar `buyer_event` depois de `hold`, invertendo a ordem de locks. Agora `ticket_count` significa **ingressos em holds ativos + pedidos confirmados** (limite de 6 por CPF e evento). Ordem de lock: `buyer_event` → `hold` → `seat_allocation`/contadores; o confirm do checkout trava apenas `hold`.
6. **SPI `HoldExpiryVeto` (A7).** `inventory` não pode consultar `payment` (de `checkout`) sem criar ciclo. `inventory` publica a SPI na raiz do módulo, `checkout` a implementa (`PendingPaymentExpiryVeto`, com janela `checkout.pending-grace`) e o Spring injeta a lista. O sweeper e a expiração preguiçosa do checkout respeitam o veto.
7. **`payments` só porta + infra (A8).** O módulo não tem `api`, `application` nem `domain`: a raiz contém a porta `PaymentGateway` (e seus resultados) e `infra` contém o adaptador HTTP (WireMock hoje, gateway real depois). Não é inconsistência, é a forma mínima.
8. **Decisão pendente da fase 3 sobre "cobrança não encontrada" (A10).** O token do cartão não é persistido; se a reconciliação consultar `GET /charges/{reference}` e o gateway responder que não encontrou a cobrança, não há como repetir a chamada. Proposta a validar na fase 3: tratar "não encontrada" como `DECLINED`, e o cliente refaz o pagamento com novo `payment`.

## Desvios assumidos na implementação

1. **`catalog` escreve em `sector_inventory` e `sector_half_quota` no cadastro de setores GA.** Viola "cada módulo escreve só nas suas tabelas" (`sector_inventory` é do `inventory`, `sector_half_quota` do `pricing`). Foi mantido na fase 1 para que o cadastro seja uma única transação sem criar dependência `catalog` → `inventory`/`pricing`. **Saída recomendada:** SPI de criação (por exemplo `SectorCreatedListener`/`SectorInventoryInitializer`) publicada na raiz do `catalog` e implementada por `inventory` e `pricing`, executada na mesma transação, de modo que cada módulo inicialize apenas as próprias tabelas. Fazer antes da fase 4, quando as regras de lote e meia passam a depender dessas tabelas.
2. **`GET /holds/{id}/checkout` no lugar do `GET` ampliado do plano.** O plano previa ampliar `GET /holds/{id}` com pagamento e pedido, mas isso faria `inventory` (dono do hold) conhecer `checkout`. O estado de pagamento e pedido fica em `checkout`, e o `GET /holds/{id}` permanece só com dados do hold.
3. **Replay de `POST /holds` devolve o corpo gravado.** A repetição com a mesma `Idempotency-Key` devolve a resposta original (`status`, `expires_at` e itens do instante da criação), mesmo que o hold já tenha expirado ou sido liberado. O estado atual é consultado em `GET /holds/{id}`.

## Alternativas consideradas

- **JPA no `catalog` e JDBC no resto:** rejeitada, dois estilos de acesso e risco de flush dentro da transação do hold.
- **Manter `seatmap_version` na transação do hold:** rejeitada, serializa o evento (A9).
- **Manter `half_*` em `sector`:** rejeitada, dois módulos escrevendo na mesma tabela (A4).
- **Manter `active_hold_id`:** rejeitada, inverte a ordem de locks e pode divergir do índice (A5).
- **`jobs` com lógica própria:** rejeitada, espalha regra de domínio fora do dono dos dados (A6).
- **`inventory` dependendo de `checkout` para consultar pagamento:** rejeitada, cria ciclo; a SPI inverte a dependência (A7).

## Consequências

- **Positivas:** fronteiras verificadas por teste (não só por convenção); transações sem estado ORM escondido; sem serialização por evento; ordem de locks única e documentada; sem ciclo entre `inventory` e `checkout`.
- **Negativas:** mapeamento manual de linhas (sem ORM); regras ArchUnit exigem manutenção das fixtures; o desvio do `catalog` precisa ser pago antes da fase 4.
- **Riscos:** a janela de veto (`checkout.pending-grace`) precisa ser maior que o timeout do gateway; um hold com pagamento `PENDING` após a janela expira e uma aprovação tardia vira reembolso (`EXPIRED_AFTER_APPROVAL`).
- **Pendente:** decisão sobre "cobrança não encontrada" (fase 3); SPI de criação de setor; versionamento do seatmap fora da transação (fase 5); estratégia de lock padrão após o benchmark da fase 2 (ADR 001).
