# Guia de testes e evidências

O objetivo é separar claramente três níveis:

1. testes automatizados de comportamento;
2. smoke test com Valkey real;
3. exercícios de falha e DR em homologação OCI.

## 1. Verificação automatizada

Execute:

```bash
./scripts/verify.sh
```

O script usa Maven local ou, se Maven não existir, uma imagem Maven no Docker.
Uma execução aprovada mostra a compilação e os dez cenários no formato
`✅ <cenário> passou`.

| Cenário | Evidência |
|---|---|
| Failover primary → standby | A escrita é repetida no standby saudável |
| Indisponibilidade total | A API interna retorna `CacheUnavailableException` |
| Backoff exponencial e jitter | Limites e progressão são verificados |
| Circuit breaker | Abre após a taxa/amostra configurada |
| Spring Cache | Namespace, TTL, `null`, `putIfAbsent` e clear O(1) |
| ACK seguro | Só ocorre depois do handler retornar com sucesso |
| Retry e DLQ | Erro transitório permanece pendente até o limite |
| Erro permanente | Vai diretamente para a DLQ |
| Falha no ACK | Não é confundida com falha de negócio |
| PEL/reclaim | `XPENDING` é consultado e o caminho de claim é executado |

Os testes ficam em:

- `src/test/java/com/example/ocicache/core/HaCacheRouterTest.java`;
- `src/test/java/com/example/ocicache/config/HaSpringCacheTest.java`;
- `src/test/java/com/example/ocicache/stream/StreamConsumerTest.java`.

## 2. Smoke test com Valkey real

Suba o ambiente:

```bash
docker compose up --build -d
docker compose logs -f app
```

Em outro terminal:

```bash
./scripts/smoke-test.sh
```

O teste verifica health, cache com TTL, publicação em Stream e idempotência do
producer.

Inspecione o estado real:

```bash
docker compose exec redis valkey-cli \
  XPENDING '{orders}:stream' orders-service

docker compose exec redis valkey-cli \
  XINFO GROUPS '{orders}:stream'

docker compose exec redis valkey-cli \
  XRANGE '{orders}:stream' - +

docker compose exec redis valkey-cli \
  XRANGE '{orders}:stream:dlq' - +
```

Com o handler de exemplo, a PEL normalmente volta rapidamente para zero e a DLQ
fica vazia.

## 3. Simular indisponibilidade local

Em um terminal, acompanhe:

```bash
docker compose logs -f app
```

Em outro:

```bash
docker compose stop redis
curl -i http://localhost:8080/api/cache/test
curl -s http://localhost:8080/api/cache-status
```

Observe retries, circuit breaker e resposta `503` quando não houver standby.
Depois restaure:

```bash
docker compose start redis
```

Confirme a recuperação:

```bash
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/api/cache-status
```

## 4. Testar DLQ sem afetar produção

O teste automatizado cobre retry, limite, erro permanente, atomicidade esperada
e ACK. Para teste integrado, faça apenas em ambiente descartável:

1. substitua temporariamente o corpo do `BusinessMessageHandler` de homologação
   por uma exceção transitória;
2. reduza `pending-min-idle` para `5s` e
   `max-processing-attempts` para `3`;
3. publique um evento com `scripts/smoke-test.sh` ou `curl`;
4. acompanhe `XPENDING` até a mensagem migrar;
5. confirme a entrada na DLQ com `XRANGE`;
6. restaure o handler e os valores antes de promover o artefato.

Para erro permanente, lance:

```java
throw new PermanentMessageProcessingException("payload inválido para o teste");
```

Nunca injete poison messages ou reduza `pending-min-idle` em produção.

## 5. Teste de failover OCI

Pré-condições:

- clusters primary e standby ativos;
- aplicação alcança os dois FQDNs por TLS/6379;
- dados usados no teste são descartáveis;
- equipe de rede/OCI acompanha;
- critério de parada e rollback definidos.

Roteiro:

1. registre `/api/cache-status` e métricas antes do teste;
2. publique um evento idempotente e grave uma chave com TTL;
3. bloqueie temporariamente o acesso da aplicação ao endpoint primário pelo
   mecanismo aprovado pela organização;
4. meça o tempo até `activeRegion` mudar;
5. repita a mesma publicação e confirme o mesmo efeito de negócio;
6. valide producer, consumer, PEL, DLQ e Spring Cache;
7. restaure a conectividade;
8. não force failback automático;
9. registre RTO, RPO, alertas disparados e gaps encontrados.

O cache da região de DR pode estar vazio. Isso é esperado quando o cache é
derivado. Streams exclusivos da região primária não são replicados por este
projeto; um log durável cross-region continua necessário.

## 6. Critério de aprovação

Uma implantação só deve avançar quando:

- os dez testes automatizados passam;
- o smoke test passa no ambiente;
- TLS, DNS e ACL foram validados;
- PEL e DLQ têm dashboards e alarmes;
- o handler é comprovadamente idempotente;
- o teste de failover mede RTO/RPO dentro do objetivo;
- existe rollback documentado;
- nenhuma credencial aparece em Git, imagem ou logs.
