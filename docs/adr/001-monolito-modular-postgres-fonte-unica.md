# ADR 001 — Monólito modular com Postgres como fonte única de verdade

- **Status:** Proposto
- **Data:** 2026-10-12
- **Plano relacionado:** [2026-10-feat-plateia-architecture](../plans/2026-10-feat-plateia-architecture.md)

## Contexto

O Plateia é uma API de venda de ingressos com assento marcado. O sistema precisa garantir que nenhum assento seja vendido duas vezes, reservar vários lugares de forma atômica, expirar holds de forma confiável e suportar a abertura das vendas com picos de cerca de 10 mil usuários simultâneos (alvo de dimensionamento). O critério de aprovação de carga é o do PRD: 5 mil usuários virtuais no k6, com uma rodada de 10 mil apenas informativa. É um projeto pessoal para exercitar concorrência, consistência e resiliência, então a arquitetura deve ser a mais simples que satisfaça esses requisitos, sem over-engineering.

## Decisão

1. **Monólito modular.** Um único módulo Gradle, pacote base `com.fastticket`, organizado por domínio (`catalog`, `pricing`, `inventory`, `payments`, `checkout`, `queue`, `realtime`, `jobs`). Cada módulo usa camadas leves (`api`, `application`, `domain`, `infra`). As dependências entre módulos são restritas e verificadas por um teste ArchUnit. O pacote `shared/*` pode ser usado por todos os módulos.
2. **Uma imagem, dois processos.** O mesmo binário roda como `api` ou `worker`, conforme o perfil Spring. Até a fase 4 os jobs rodam no processo da API (perfil combinado) e, por isso, todo job precisa ser seguro com várias réplicas: os itens são reivindicados com `FOR UPDATE SKIP LOCKED`.
3. **Postgres é a única verdade e o único relógio.** Invariantes ficam em constraints e índices únicos parciais, a idempotência fica em tabela e toda decisão de prazo usa o relógio do banco. Como o `now()` do Postgres fica fixo no início da transação, o prazo do hold (`expires_at`) e as decisões de prazo tomadas após a espera por um lock usam `clock_timestamp()`, avaliado depois que os locks foram obtidos.
4. **Redis só onde o Postgres seria gargalo**, a partir da fase 5: fila virtual, pub/sub do SSE, cache do seatmap e rate limit. Nenhuma regra de negócio depende dele. O seatmap carrega uma `seatmap_version` (incrementada fora da transação do hold, após o commit; emenda no [ADR 002](002-fronteiras-de-modulo-e-persistencia.md)), e o cache tem TTL curto (2 a 5 s) como rede de segurança contra invalidações perdidas.
5. **Pagamento síncrono, somente cartão.** Crédito e débito online, sem Pix nem boleto, e sem webhook. O gateway fica atrás da porta `PaymentGateway`, e o mock é um container WireMock com injeção de falhas. A chamada ao gateway nunca ocorre dentro de uma transação do banco: tx1 cria o `payment PENDING`, o gateway é chamado, e a tx2 aplica o resultado. O resultado tem três estados: `APPROVED`, `DECLINED` e `UNKNOWN` (timeout ou erro 5xx, em que a cobrança pode ter ocorrido). `UNKNOWN` nunca é tratado como recusa. Ele mantém o pagamento `PENDING` e é resolvido por um job de reconciliação, com `GET /charges/{reference}` ou nova chamada idempotente com a mesma `reference`. A API não recebe nem guarda número de cartão, apenas tokens do gateway.
6. **Sem outbox.** O `payment` ou `refund` em `PENDING` já é a intenção durável, e o job de reconciliação os retoma. Todas as chamadas ao gateway são idempotentes por `reference`, o que impede cobrança dupla mesmo se duas réplicas repetirem a chamada.
7. **Entrega em fases.** A fase 0 é preparação (fundação técnica, com critério técnico de pronto) e não conta como fase de entrega. As fases 1 a 5 são as cinco fases de entrega do PRD, cada uma liberada por uma prova objetiva (invariantes, testes de concorrência, chaos test, k6). A expiração de holds entra na fase 1, pois sem ela os assentos de holds abandonados ficariam presos e contaminariam o benchmark da fase 2.

## Alternativas consideradas

- **Microserviços:** rejeitado. Adicionaria consistência distribuída num domínio cujo ponto central é a consistência forte, e não se justifica no dimensionamento previsto.
- **Multi-módulo Gradle por domínio:** rejeitado. O ganho de isolamento não compensa o custo de configuração neste tamanho. O ArchUnit cobre as fronteiras.
- **TTL no Redis para expiração de holds:** rejeitado. Não é transacional com o banco e usaria outro relógio.
- **Webhook e outbox para pagamentos:** rejeitado. Só se justificam com meios de pagamento assíncronos (Pix, boleto). Para cartão síncrono, o `payment PENDING` mais a reconciliação cobrem o mesmo risco com menos peças, e eliminam a dependência de `payments` para `checkout` que um webhook criaria.
- **Outbox durável de eventos do seatmap:** rejeitado. A versão incrementada na transação, o cliente que refaz o `GET` ao ver um salto de versão e o TTL curto do cache resolvem a perda de uma publicação sem outra tabela.
- **Kafka, CQRS e Kubernetes:** fora de escopo para este dimensionamento.

## Consequências

- **Positivas:** invariantes garantidas pelo banco, independentes da estratégia de lock; transações curtas e simples; poucas peças de infraestrutura; trocar a estratégia de lock não gera assento duplicado; sem webhook, sem outbox e sem autenticação de callback para manter.
- **Negativas:** a escrita fica limitada à primária do Postgres (suficiente: a fila reduz a carga para dezenas de holds por segundo); a disciplina de fronteiras entre módulos depende de testes, e não do compilador; o gateway síncrono exige tratar `UNKNOWN` com cuidado e uma reconciliação confiável.
- **Riscos:** crescimento de escopo antes da fase 2 estar sólida; compatibilidade dos starters de Redis e Micrometer com o Spring Boot 4.1.1 (verificar antes da fase 5); o débito online real costuma exigir autenticação assíncrona do banco, então o adapter real pode reintroduzir um callback (a porta aceita `PENDING` como resultado válido).
- **Desvios do PRD e da arquitetura original:** o PRD menciona callback duplicado no mock do gateway. Aqui isso vira timeout após o processamento (cobrou, mas a resposta se perdeu) e repetição de chamada com a mesma `reference`. Webhook, outbox e meios de pagamento assíncronos saem do escopo.
- **Decidido na fase 2:** estratégia de lock padrão (`conditional`), ver [ADR 003](003-estrategia-padrao-de-alocacao-de-assentos.md).
