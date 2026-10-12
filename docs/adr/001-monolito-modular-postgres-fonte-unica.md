# ADR 001 — Monólito modular com Postgres como fonte única de verdade

- **Status:** Proposto
- **Data:** 2026-10-12
- **Plano relacionado:** [2026-10-feat-plateia-architecture](../plans/2026-10-feat-plateia-architecture.md)

## Contexto

O Plateia é uma API de venda de ingressos com assento marcado. O sistema precisa garantir que nenhum assento seja vendido duas vezes, reservar vários lugares de forma atômica, expirar holds de forma confiável e suportar a abertura das vendas com picos de cerca de 10 mil usuários simultâneos. É um projeto pessoal para exercitar concorrência, consistência e resiliência, então a arquitetura deve ser a mais simples que satisfaça esses requisitos, sem over-engineering.

## Decisão

1. **Monólito modular.** Um único módulo Gradle, pacote base `com.fastticket`, organizado por domínio (`catalog`, `pricing`, `inventory`, `payments`, `checkout`, `queue`, `realtime`, `jobs`). Cada módulo usa camadas leves (`api`, `application`, `domain`, `infra`). As dependências entre módulos são restritas e verificadas por um teste ArchUnit.
2. **Uma imagem, dois processos.** O mesmo binário roda como `api` ou `worker`, conforme o perfil Spring. Até a fase 4 os jobs rodam no processo da API (perfil combinado).
3. **Postgres é a única verdade e o único relógio.** Invariantes ficam em constraints e índices únicos parciais, a idempotência fica em tabela e toda decisão de prazo usa `now()` do banco.
4. **Redis só onde o Postgres seria gargalo**, a partir da fase 5: fila virtual, pub/sub do SSE, cache do seatmap e rate limit. Nenhuma regra de negócio depende dele.
5. **Gateway de pagamento atrás de uma porta** (`PaymentGateway`). O mock é um container WireMock com injeção de falhas (latência, timeout e callback duplicado).
6. **Entrega em 5 fases**, cada uma liberada por uma prova objetiva (invariantes, teste de concorrência, chaos test, k6).

## Alternativas consideradas

- **Microserviços:** rejeitado. Adicionaria consistência distribuída num domínio cujo ponto central é a consistência forte, e não se justifica a 10 mil usuários.
- **Multi-módulo Gradle por domínio:** rejeitado. O ganho de isolamento não compensa o custo de configuração neste tamanho. O ArchUnit cobre as fronteiras.
- **TTL no Redis para expiração de holds:** rejeitado. Não é transacional com o banco e usaria outro relógio.
- **Kafka, CQRS e Kubernetes:** fora de escopo para este dimensionamento.

## Consequências

- **Positivas:** invariantes garantidas pelo banco, independentes da estratégia de lock; transações curtas e simples; operação barata (poucas peças de infraestrutura); trocar a estratégia de lock não gera assento duplicado.
- **Negativas:** escala da escrita limitada à primária do Postgres (suficiente: a fila reduz a carga para dezenas de holds por segundo); a disciplina de fronteiras entre módulos depende de testes, e não do compilador.
- **Riscos:** crescimento de escopo antes da fase 2 estar sólida; compatibilidade dos starters de Redis e Micrometer com o Spring Boot 4.1.1 (verificar antes da fase 5).
- **Pendente (ADRs futuros):** estratégia de lock padrão após o benchmark da fase 2.
