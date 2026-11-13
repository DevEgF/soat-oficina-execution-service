# soat-oficina-execution-service

[![CI](https://github.com/DevEgF/soat-oficina-execution-service/actions/workflows/ci.yml/badge.svg?branch=develop)](https://github.com/DevEgF/soat-oficina-execution-service/actions/workflows/ci.yml)
[![Cobertura](https://sonarcloud.io/api/project_badges/measure?project=DevEgF_soat-oficina-execution-service&metric=coverage)](https://sonarcloud.io/summary/new_code?id=DevEgF_soat-oficina-execution-service)
[![Quality gate](https://sonarcloud.io/api/project_badges/measure?project=DevEgF_soat-oficina-execution-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=DevEgF_soat-oficina-execution-service)

## O que é

Serviço de **Execução** da oficina mecânica (Tech Challenge SOAT, Fase 4): dono da fila de
execução, do diagnóstico, do reparo, da finalização e da falha de cada ordem de serviço (OS).
É um dos três microsserviços que substituem o monólito `soat-oficina-app`; os outros são
`soat-oficina-os-service` e `soat-oficina-billing-service`. É o serviço que usa o banco
**NoSQL** da plataforma (MongoDB).

Na saga orquestrada pelo OS Service, o Execution é participante: recebe o comando
`StartExecution` depois do pagamento aprovado, coloca a OS na fila e, conforme a equipe avança
a execução pela API, publica `ExecutionStatusChanged`, `ExecutionFinished` ou `ExecutionFailed`.

Stack: Kotlin 2.4.10, Spring Boot 4.1.2, Java 17, Gradle 9.4.0 (wrapper), Spring Web, Security
(OAuth2 Resource Server, JWT HS256), Actuator, springdoc 3.1.0, MongoDB 8 e RabbitMQ.

## Arquitetura

Camadas `presentation` (controllers REST), `application` (casos de uso e portas),
`domain` (agregado `Execution` com a máquina de estados) e `infrastructure` (MongoDB, RabbitMQ,
segurança, observabilidade). Diagramas de componentes, da participação na saga e do modelo de
dados em [docs/arquitetura.md](docs/arquitetura.md).

## Como rodar

### Fora do cluster (perfil `local`)

Pré-requisitos: JDK 17, Docker. As dependências (MongoDB na porta 27017, usuário root
`execution` no banco `admin`, e RabbitMQ na 5672) vêm do `docker-compose.yml` da plataforma,
em `soat-oficina-infra-k8s/local/`:

```bash
docker compose --env-file ../.env.local -f ../soat-oficina-infra-k8s/local/docker-compose.yml up -d --wait rabbitmq mongo
./gradlew bootRun
```

O `bootRun` ativa o perfil `local` (ambiente `hml` e um segredo JWT de desenvolvimento);
variáveis de ambiente continuam valendo por cima. A API sobe em `http://localhost:8083`:

- `GET /actuator/health` (público; `/actuator/health/liveness` e `/actuator/health/readiness`)
- `GET /api/info` (exige `Authorization: Bearer <jwt>`)
- `GET /swagger-ui.html` e `GET /v3/api-docs` (públicos)

O token é HS256 assinado com SHA-256 do `APP_JWT_SECRET`, com `iss=oficina`,
`aud=oficina-api` e `env` igual ao `APP_ENVIRONMENT`. Rotas fora de `/api/**` e das públicas
são negadas.

### No kind

O cluster `oficina` (contexto `kind-oficina`), o namespace `oficina`, o RabbitMQ e o Secret
`execution-service-secrets` vêm de `soat-oficina-infra-k8s/local/` (`up.sh` e `secrets.sh`).
O MongoDB do serviço (`execution-mongo`, StatefulSet de 1 réplica com PVC de 1Gi e
autenticação ligada) é implantado junto, por `k8s/mongo.yaml`.

```bash
scripts/deploy-kind.sh all
curl http://localhost:30083/actuator/health
```

Subcomandos de `scripts/deploy-kind.sh`:

| Subcomando | O que faz |
|---|---|
| `build` | `docker build` de `soat-oficina-execution-service:$IMAGE_TAG` |
| `load` | `kind load docker-image` no cluster `oficina` |
| `apply` | overlay kustomize temporário com a tag e `kubectl apply -k` |
| `wait` | `kubectl rollout status` do banco e do serviço |
| `smoke` | `GET /actuator/health` no NodePort 30083 até `UP` |
| `rollback` | `kubectl rollout undo` + espera (no primeiro deploy só avisa) |
| `diagnose` | pods, eventos, describe e logs |
| `all` | build, load, apply, wait e smoke; se falhar, rollback e diagnóstico |

Variáveis: `IMAGE_TAG` (padrão: SHA curto do git ou `local`; defina outra tag se o commit não
mudou, senão o cluster mantém a imagem anterior), `KIND_CLUSTER=oficina`,
`KUBE_CONTEXT=kind-oficina`, `NAMESPACE=oficina`, `ROLLOUT_TIMEOUT=300s`.

No GitHub, o workflow `Deploy` roda o mesmo script no runner self-hosted (`oficina-kind`)
depois de um `CI` verde de push na `main`.

## API

Todas em `/api/**`, com `Authorization: Bearer <jwt>`; aparecem no OpenAPI (`/swagger-ui.html`).

| Rota | O que faz |
|---|---|
| `GET /api/executions?status=QUEUED` | lista por ordem de entrada na fila (mais antiga primeiro); sem `status`, lista todas |
| `GET /api/executions/{workOrderId}` | consulta a execução, com datas e a lista `events` |
| `POST /api/executions/{workOrderId}/diagnosis/start` | `QUEUED` -> `IN_DIAGNOSIS` |
| `POST /api/executions/{workOrderId}/repair/start` | `IN_DIAGNOSIS` -> `IN_EXECUTION` |
| `POST /api/executions/{workOrderId}/finish` | `IN_EXECUTION` -> `FINISHED` |
| `POST /api/executions/{workOrderId}/fail` | `QUEUED`, `IN_DIAGNOSIS` ou `IN_EXECUTION` -> `FAILED`; corpo `{ "reason": "texto" }` |

Os `POST` respondem 200 com o mesmo formato do `GET` (que traz também `failedAt` e
`failureReason`); 404 para OS desconhecida e 409 para transição inválida (ou atualização
concorrente). Só o `fail` tem corpo, e `reason` é obrigatório e não pode ser vazio (400 caso
contrário); a falha não é possível a partir de `FINISHED` nem de `FAILED` (409). Status: `QUEUED`,
`IN_DIAGNOSIS`, `IN_EXECUTION`, `FINISHED` e `FAILED`.

Swagger UI em `/swagger-ui.html` e OpenAPI em `/v3/api-docs`. Coleção Postman da plataforma:
[soat-oficina-infra-k8s/postman](https://github.com/DevEgF/soat-oficina-infra-k8s/tree/develop/postman).

## Banco

MongoDB, banco `execution`.

| Coleção | O que guarda |
|---|---|
| `execucoes` | um documento por OS (`_id` e `workOrderId` iguais ao id da OS): dados do veículo e do cliente, serviços e peças, status, datas de cada etapa, motivo da falha, `correlationId` da saga, lista `events` e versão para bloqueio otimista |
| `processed_messages` | `_id` = `messageId` das mensagens já tratadas (idempotência), com `type` e `processedAt` |

Os índices são criados na subida: único em `workOrderId` e composto em `status` + `queuedAt`. O MongoDB do
cluster é standalone, sem transação multidocumento: o efeito de cada mensagem é idempotente por
si (índice único) e o `messageId` é gravado depois dele.

## Mensagens

Exchange `oficina` (topic) e `oficina.dlx` (direct), declarados na subida junto com as filas
`execution.commands` (binding `command.execution.#`) e `os.events` (binding `event.#`), cada uma
com a DLQ `<fila>.dlq` (`x-dead-letter-exchange=oficina.dlx`, `x-dead-letter-routing-key=<fila>.dlq`).

| Direção | `type` | Routing key | Efeito |
|---|---|---|---|
| consome | `StartExecution` | `command.execution.start-execution` | cria a execução `QUEUED`; repetição (mesmo `messageId` ou mesma OS) não duplica |
| publica | `ExecutionStatusChanged` | `event.execution.status-changed` | `status` = `IN_DIAGNOSIS` ou `IN_EXECUTION` |
| publica | `ExecutionFinished` | `event.execution.finished` | `finishedAt` |
| publica | `ExecutionFailed` | `event.execution.failed` | `reason`; publicado por `POST .../fail` |

Envelope JSON (`messageId`, `sagaId` = id da OS, `type`, `occurredAt`, `correlationId`,
`payload`) serializado com o `ObjectMapper`, sem `__TypeId__`. O `correlationId` recebido é
guardado na execução e reutilizado nos eventos publicados, inclusive os disparados por HTTP; no
consumidor ele vai para o MDC (chave `requestId`). O listener faz 3 tentativas (500 ms de
intervalo inicial, dobrando) e, esgotadas, rejeita sem requeue (DLQ). JSON inválido, campos
obrigatórios ausentes e `type` fora do contrato vão direto para a DLQ, sem retry. Tipos do
contrato que não são deste serviço geram log WARN e ack. O evento é publicado
depois de gravar o documento; se a publicação falhar, a chamada HTTP responde 500 com o estado
já gravado (um outbox fica como evolução).

## Configuração

No Spring Boot 4 as propriedades de conexão do MongoDB são `spring.mongodb.*`
(as antigas `spring.data.mongodb.*` de conexão estão descontinuadas).

| Variável | Origem no cluster | Padrão fora do cluster |
|---|---|---|
| `SERVER_PORT` | ConfigMap (`8080`) | `8083` |
| `APP_ENVIRONMENT` | ConfigMap (`hml`) | obrigatório (perfil `local`: `hml`) |
| `APP_JWT_SECRET` | Secret | obrigatório (perfil `local`: segredo de desenvolvimento) |
| `SPRING_MONGODB_HOST` / `_PORT` | ConfigMap (`execution-mongo` / `27017`) | `localhost` / `27017` |
| `SPRING_MONGODB_DATABASE` | ConfigMap (`execution`) | `execution` |
| `SPRING_MONGODB_USERNAME` | ConfigMap (`execution`) | `execution` |
| `SPRING_MONGODB_AUTHENTICATION_DATABASE` | ConfigMap (`admin`) | `admin` |
| `MONGODB_PASSWORD` | Secret | `oficina` |
| `SPRING_RABBITMQ_HOST` / `_PORT` / `_USERNAME` | ConfigMap (`rabbitmq` / `5672` / `oficina`) | `localhost` / `5672` / `oficina` |
| `SPRING_RABBITMQ_PASSWORD` | Secret | `oficina` |
| `NEW_RELIC_APP_NAME` e demais `NEW_RELIC_*` | ConfigMap (`oficina-execution-service`) | — |
| `NEW_RELIC_LICENSE_KEY` | Secret (vazio desliga o agente) | — |

O grupo de readiness do Actuator inclui `mongo` e `rabbit`: o pod só recebe tráfego com o
banco e o broker acessíveis.

### Contrato do Secret `execution-service-secrets`

| Chave | Uso |
|---|---|
| `APP_JWT_SECRET` | segredo HS256 compartilhado entre os serviços (mínimo 32 bytes) |
| `MONGODB_PASSWORD` | senha da aplicação **e** do usuário root `execution` do MongoDB `execution-mongo` |
| `SPRING_RABBITMQ_PASSWORD` | senha do usuário `oficina` no RabbitMQ |
| `NEW_RELIC_LICENSE_KEY` | license key do New Relic; vazio desliga o agente |

Modelo só com placeholders em `k8s/secret.example.yaml` (fora do `kustomization.yaml`).
A senha do MongoDB só é aplicada na primeira inicialização do volume; para trocá-la depois,
apague o PVC `data-execution-mongo-0` (perde os dados) ou altere o usuário pelo `mongosh`.

## Qualidade

- **CI:** o job `ci / build` roda `./gradlew check` e, quando o secret `SONAR_TOKEN` existe, a
  análise do Sonar com `-Dsonar.qualitygate.wait=true`; o job `ci / image` faz o build da imagem,
  sem push. O CI roda em PRs e em pushes na `main` e na `develop`; o plano gratuito do SonarQube
  Cloud não analisa outras branches, então o Sonar roda só em PRs e na `main`. O badge do CI mostra
  a `develop`; os do Sonar, a `main`.
- **Gate de cobertura:** `./gradlew check` executa os testes unitários e o de integração (contexto
  completo contra MongoDB e RabbitMQ reais, via Testcontainers; precisa do Docker), gera o JaCoCo em
  `build/reports/jacoco/test/` (XML e HTML) e falha se a cobertura de linhas do serviço inteiro
  ficar abaixo de 80%. Só a classe principal fica fora da conta (a mesma exclusão vai para o Sonar).
- **Sonar:** projeto `DevEgF_soat-oficina-execution-service` (organização `devegf-soat-oficina`),
  [resumo do novo código](https://sonarcloud.io/summary/new_code?id=DevEgF_soat-oficina-execution-service).
  A análise é feita pelo CI (`./gradlew sonar`); a análise automática do SonarQube Cloud não se
  aplica, porque o projeto não está vinculado ao GitHub.
- **Verificação de dependências:** `gradle/verification-metadata.xml` guarda o SHA-256 de cada
  plugin e dependência; o Gradle recusa artefato com checksum diferente. Ao mudar uma versão ou
  acrescentar uma dependência, regenere o arquivo e confira o diff:

  ```bash
  ./gradlew --no-daemon --write-verification-metadata sha256 --refresh-dependencies check bootJar sonarResolver
  ./gradlew --no-daemon --refresh-dependencies check bootJar sonarResolver   # sem escrever: tem de passar
  docker build -t soat-oficina-execution-service:verif .                      # o build da imagem também resolve dependências
  ```

## Observabilidade

- **New Relic:** aplicação `oficina-execution-service` (`NEW_RELIC_APP_NAME`). O agente Java
  (9.4.0) vai na imagem e só liga com `NEW_RELIC_LICENSE_KEY` preenchida; a dependência
  `newrelic-api` (mesma versão) é no-op sem o agente.
- **Trace distribuído pelo RabbitMQ:** o agente coloca `traceparent`, `tracestate` e `newrelic`
  nos cabeçalhos das mensagens publicadas durante uma requisição HTTP. O processamento de uma
  mensagem consumida não é uma transação do agente por padrão, então o
  `ExecutionCommandListener` abre uma (`@Trace(dispatcher = true)`), aceita os cabeçalhos de
  trace da mensagem pelo adaptador `NewRelicTracing`, nomeia a transação (`Message`,
  `<fila>/<type>`) e registra os atributos `sagaId`, `messageType`, `messageId` e
  `correlationId`. O que for publicado dentro dela continua o mesmo trace.
- **Logs:** estruturados (logstash) no cluster e encaminhados pelo agente com o contexto do MDC
  (`NEW_RELIC_APPLICATION_LOGGING_FORWARDING_CONTEXT_DATA_ENABLED`). Durante uma mensagem o MDC
  tem `sagaId`, `messageType`, `messageId` e `requestId` (o `correlationId` da saga); nas
  requisições HTTP, `requestId`, `environment` e `service`.

## Saga

Este serviço é participante da saga de OS orquestrada pelo OS Service: executa `StartExecution`
(coloca a OS na fila de execução, de forma idempotente) e informa o andamento com
`ExecutionStatusChanged`, `ExecutionFinished` e `ExecutionFailed`. Quando uma execução falha, o
`ExecutionFailed` é o gatilho para o orquestrador compensar (estornar o pagamento, cancelar o
orçamento e cancelar a OS); o Execution não desfaz nada por conta própria. Detalhes, tabelas de passos e de
compensações em
[soat-oficina-os-service](https://github.com/DevEgF/soat-oficina-os-service/tree/develop#saga).

## Proteção da `main`

`scripts/configure-github.sh` aplica, via GitHub CLI, o ruleset `soat-protection-main`
(pull request obrigatório, checks `ci / build` e `ci / image` obrigatórios, sem force-push e
sem deleção), exige aprovação para workflows de PRs de fork e grava o secret `SONAR_TOKEN`
quando a variável está no ambiente. O deploy (`deploy / kind`) só roda depois de um CI verde
de push na `main`, em runner self-hosted registrado por `local/runners.sh` do repositório
`soat-oficina-infra-k8s`.
