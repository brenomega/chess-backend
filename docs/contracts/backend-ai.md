# Contrato Backend-IA

O worker consulta o Backend por HTTP/JSON e fornece apenas um movimento candidato. O Backend continua responsável por estado, relógio, revisão, legalidade e conclusão da partida.

`jobId` e `gameId` são UUIDs, e a posição é FEN. `revision` é a revisão autoritativa da partida que originou o trabalho.

A versão inicial do contrato é `v1`, identificada no primeiro segmento do caminho após a raiz. Não há negociação por cabeçalho. Mudanças incompatíveis usam uma nova versão de caminho, e Backend e worker não devem fazer fallback automático entre versões. Requisições e respostas com corpo usam `Content-Type: application/json`.

## Obter trabalho

`POST /v1/ai/jobs/next` recebe:

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

Quando não houver trabalho disponível, responde `204` sem corpo. Uma atribuição fica reservada ao worker até `expiresAt`. Se expirar sem resultado válido e ainda corresponder ao turno, o mesmo `jobId` pode ser atribuído novamente, inclusive a outro worker.

## Entregar candidato

`POST /v1/ai/jobs/result` recebe:

```json
{
  "workerId":"worker-1",
  "jobId":"70e4c8e1-1a9e-41bc-a8d6-232d50d15435",
  "gameId":"d7fc48b7-570a-4d8d-a7ae-1bc5d20a9c13",
  "revision":12,
  "uci":"e7e5"
}
```

O Backend verifica a titularidade do trabalho antes de tratar reenvios. Para um trabalho em aberto, confere `gameId`, prazo, revisão e estado da partida, e valida o UCI pelas regras autoritativas. A resposta `200` contém `{"status":"APPLIED","gameId":"...","revision":13}`. Um reenvio idêntico pelo proprietário retorna a mesma resposta sem executar outra transição. Alterar a revisão ou o UCI de um trabalho concluído retorna `JOB_ALREADY_COMPLETED`. Um `gameId` divergente retorna `VALIDATION_ERROR` ao proprietário.

## Erros

O corpo é `{"code":"...","message":"...","details":{}}`.

`/next` e `/result` exigem um corpo JSON e um `workerId` de até 64 caracteres com pelo menos um caractere que não seja espaço em branco. Um corpo ausente ou ilegível, um UUID malformado, um campo obrigatório ausente ou um `workerId` inválido retorna `400 VALIDATION_ERROR`.

| Status | Códigos essenciais |
| --- | --- |
| `400` | `VALIDATION_ERROR`, `INVALID_CANDIDATE` |
| `404` | `JOB_NOT_FOUND` |
| `409` | `JOB_EXPIRED`, `STALE_REVISION`, `JOB_ALREADY_COMPLETED`, `WORKER_OWNERSHIP_CONFLICT` |
| `422` | `ILLEGAL_MOVE`, `INVALID_GAME_STATE` |

`WORKER_OWNERSHIP_CONFLICT` indica que o `jobId` está atribuído a outro `workerId`. A entrega é rejeitada sem alterar o trabalho ou a partida. O worker rejeitado não repete essa entrega: descarta o trabalho local e volta a solicitar trabalho em `/v1/ai/jobs/next`. O worker proprietário ainda pode entregar o resultado enquanto o trabalho estiver válido.

Trabalhos cancelados retornam `JOB_EXPIRED` ao proprietário. Se o candidato chega com a atribuição válida e o relógio da partida já se esgotou, o Backend persiste o fim por `TIMEOUT`, cancela o trabalho e responde `INVALID_GAME_STATE`.

## Consistência da fila

Para uma partida existente, a transação trava uma única linha de `game` antes de alterar `ai_job`. A consulta inicial de resultado lê somente o ID da partida. O polling seleciona a partida com `FOR UPDATE OF g SKIP LOCKED` e relê o trabalho após obter a trava. Criação e ações gravam a partida antes de reconciliar a fila na mesma transação. Conclusão ou cancelamento é enviado ao banco antes de inserir um substituto, respeitando o índice de trabalho aberto por partida. Após o commit, o callback apenas publica o snapshot.

O contrato não especifica algoritmo, profundidade de busca, avaliação, livro de aberturas ou motor utilizado pelo worker.
