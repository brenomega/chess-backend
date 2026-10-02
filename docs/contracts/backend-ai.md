# Contrato Backend-IA

O worker consulta o Backend por HTTP/JSON e fornece apenas um movimento candidato. O Backend continua responsável por estado, relógio, revisão, legalidade e conclusão da partida.

`jobId` e `gameId` são UUIDs, e a posição é FEN. `revision` é a revisão autoritativa da partida que originou o trabalho.

## Obter trabalho

`POST /next` recebe:

```json
{"workerId":"worker-1"}
```

Quando houver trabalho disponível, responde `200`:

```json
{
  "jobId":"70e4c8e1-1a9e-41bc-a8d6-232d50d15435",
  "gameId":"d7fc48b7-570a-4d8d-a7ae-1bc5d20a9c13",
  "revision":12,
  "position":{"fen":"...","sideToMove":"BLACK"},
  "timeBudgetMs":1000,
  "expiresAt":"2026-10-02T15:00:00Z"
}
```

Quando não houver trabalho, responde `204` sem corpo. Um mesmo `jobId` pode ser reapresentado ao mesmo worker até receber resultado ou expirar.

## Entregar candidato

`POST /result` recebe:

```json
{
  "workerId":"worker-1",
  "jobId":"70e4c8e1-1a9e-41bc-a8d6-232d50d15435",
  "gameId":"d7fc48b7-570a-4d8d-a7ae-1bc5d20a9c13",
  "revision":12,
  "uci":"e7e5"
}
```

O Backend valida que o trabalho pertence ao worker, não expirou e ainda corresponde à revisão em jogo. Também valida o UCI pelas regras autoritativas antes de aplicar o movimento. A resposta `200` contém `{"status":"APPLIED","gameId":"...","revision":13}`. Uma repetição idêntica de resultado já aplicado retorna o mesmo resultado, sem executar uma segunda transição; um resultado diferente para trabalho concluído retorna `JOB_ALREADY_COMPLETED`.

## Erros

O corpo é `{"code":"...","message":"..."}`.

| Status | Códigos essenciais |
| --- | --- |
| `400` | `VALIDATION_ERROR`, `INVALID_CANDIDATE` |
| `404` | `JOB_NOT_FOUND` |
| `409` | `JOB_EXPIRED`, `STALE_REVISION`, `JOB_ALREADY_COMPLETED` |
| `422` | `ILLEGAL_MOVE`, `INVALID_GAME_STATE` |

O contrato não especifica algoritmo, profundidade de busca, avaliação, livro de aberturas ou motor utilizado pelo worker.
