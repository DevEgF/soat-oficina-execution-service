# Arquitetura do Execution Service

O serviço guarda a execução de cada ordem de serviço (OS) e participa da saga orquestrada pelo
OS Service. Não acessa o banco de outro serviço: conversa com os demais só pelo contrato de
mensagens no RabbitMQ. A visão geral e como rodar estão no [README](../README.md).

## Componentes por camada

```mermaid
flowchart TB
    subgraph apresentacao["Apresentação"]
        EC["ExecutionController<br/>/api/executions"]
        IC["InfoController<br/>/api/info"]
        REH["RestExceptionHandler"]
    end

    subgraph aplicacao["Aplicação"]
        EAS["ExecutionApplicationService<br/>queue, list, get, startDiagnosis,<br/>startRepair, finish, fail"]
        PORTS["Portas<br/>ExecutionRepository<br/>ProcessedMessageStore<br/>ExecutionEventPublisher"]
    end

    subgraph dominio["Domínio"]
        EXE["Execution<br/>máquina de estados"]
        ST["ExecutionStatus"]
        EXC["Exceções de domínio"]
    end

    subgraph infra["Infraestrutura e adaptadores"]
        subgraph entrada["Entrada"]
            CIF["CorrelationIdFilter"]
            SEC["SecurityConfiguration<br/>JWT HS256"]
            LST["ExecutionCommandListener<br/>fila execution.commands"]
            HND["ExecutionCommandHandler"]
            COD["EnvelopeCodec"]
        end
        subgraph saida["Saída"]
            REPO["MongoExecutionRepository"]
            PMS["MongoProcessedMessageStore"]
            PUB["RabbitExecutionEventPublisher"]
        end
        TR["NewRelicTracing<br/>(MessageTracing)"]
    end

    MONGO[("MongoDB")]
    RABBIT{{"RabbitMQ<br/>exchange oficina"}}
    NR["Agente New Relic"]

    EC --> EAS
    IC --> EAS
    EC -. erros .-> REH
    LST --> COD
    LST --> TR
    LST --> HND
    HND --> PORTS
    HND --> EAS
    EAS --> EXE
    EXE --> ST
    EAS --> PORTS
    PORTS -. implementadas por .-> REPO
    PORTS -. implementadas por .-> PMS
    PORTS -. implementadas por .-> PUB
    REPO --> MONGO
    PMS --> MONGO
    PUB --> RABBIT
    RABBIT --> LST
    TR --> NR
```

As dependências apontam para dentro: a infraestrutura implementa as portas definidas na
aplicação, e o domínio não conhece Spring, MongoDB nem RabbitMQ. O adaptador `NewRelicTracing`
isola a API do New Relic atrás da interface `MessageTracing`; sem o agente ela não faz nada.

## Participação na saga

```mermaid
sequenceDiagram
    autonumber
    participant OS as OS Service (orquestrador)
    participant MQ as RabbitMQ (oficina)
    participant L as ExecutionCommandListener
    participant H as ExecutionCommandHandler
    participant S as ExecutionApplicationService
    participant DB as MongoDB
    actor T as Equipe (API HTTP)

    OS->>MQ: StartExecution (command.execution.start-execution)
    MQ->>L: entrega em execution.commands
    L->>L: aceita cabeçalhos de trace, nomeia a transação, preenche o MDC
    L->>H: envelope decodificado
    H->>DB: messageId já processado?
    alt já processado
        H-->>L: ignora (message.duplicate)
    else nova
        H->>S: queue(workOrderId, ...)
        S->>DB: insere execução QUEUED (índice único por OS)
        H->>DB: grava messageId em processed_messages
    end
    L-->>MQ: ack

    T->>S: POST diagnosis/start
    S->>DB: QUEUED -> IN_DIAGNOSIS
    S->>MQ: ExecutionStatusChanged (IN_DIAGNOSIS)
    T->>S: POST repair/start
    S->>DB: IN_DIAGNOSIS -> IN_EXECUTION
    S->>MQ: ExecutionStatusChanged (IN_EXECUTION)

    alt reparo concluído
        T->>S: POST finish
        S->>DB: IN_EXECUTION -> FINISHED
        S->>MQ: ExecutionFinished
    else falha em qualquer etapa não final
        T->>S: POST fail (reason)
        S->>DB: status -> FAILED
        S->>MQ: ExecutionFailed
        MQ->>OS: o orquestrador cancela a OS e dispara as compensações
    end
```

Cada evento é publicado depois de gravar o documento, com o `correlationId` recebido em
`StartExecution`. Mensagem ilegível ou de `type` desconhecido vai direto para a DLQ
(`execution.commands.dlq`); outras falhas passam por três tentativas antes de ir para a DLQ.

## Modelo de dados

```mermaid
erDiagram
    EXECUCOES {
        string _id "workOrderId"
        long version "bloqueio otimista"
        string workOrderId "índice único"
        string trackingCode
        string customerName
        object vehicle "plate, brand, model, year"
        array services "code, quantity"
        array parts "code, quantity"
        string status "índice com queuedAt"
        string correlationId
        instant queuedAt
        instant diagnosisStartedAt
        instant repairStartedAt
        instant finishedAt
        instant failedAt
        string failureReason
        array events "type, status, occurredAt"
    }
    PROCESSED_MESSAGES {
        string _id "messageId"
        string type
        instant processedAt
    }
    EXECUCOES ||--o{ PROCESSED_MESSAGES : "idempotência de StartExecution"
```

`processed_messages` não tem chave estrangeira: a relação é lógica (o `messageId` consumido
produziu, no máximo, uma execução). O MongoDB do cluster é standalone, sem transação
multidocumento, por isso cada escrita é de um único documento e o efeito de cada mensagem é
idempotente por si.

### Estados da execução

```mermaid
stateDiagram-v2
    [*] --> QUEUED: StartExecution
    QUEUED --> IN_DIAGNOSIS: diagnosis/start
    IN_DIAGNOSIS --> IN_EXECUTION: repair/start
    IN_EXECUTION --> FINISHED: finish
    QUEUED --> FAILED: fail
    IN_DIAGNOSIS --> FAILED: fail
    IN_EXECUTION --> FAILED: fail
    FINISHED --> [*]
    FAILED --> [*]
```
