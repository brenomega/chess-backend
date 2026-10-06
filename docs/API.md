# Contrato REST e WebSocket

O cliente envia JSON em UTF-8. Identificadores são UUIDs, instantes usam ISO-8601 UTC e durações usam milissegundos. O valor opaco `recoveryToken` retornado pela sessão é enviado em `X-Session-Token` para resolver a identidade guest; não há autenticação global.

## REST

| Operação | Requisição | Resposta de sucesso |
| --- | --- | --- |
| `POST /v1/sessions` | corpo vazio | `201` com `sessionId`, `recoveryToken` e `expiresAt` |
| `POST /v1/sessions/recover` | `{"recoveryToken":"..."}` | `200` com `sessionId`, `recoveryToken` e `expiresAt` |
| `GET /v1/games?page=0&size=20` | token de sessão | `200` com página de `games`, contendo `gameId`, `status`, `visibility`, `timeControl` e `createdAt` |
| `POST /v1/games` | token de sessão; `{"visibility":"PUBLIC|PRIVATE","timeControl":{"initialTimeMs":600000,"incrementMs":0}}` | `201` com o snapshot da partida em espera |
| `POST /v1/games/{gameId}/join` | token de sessão; corpo `{"entryCode":"G7K2XP"}` para partida `PRIVATE` ou vazio para `PUBLIC` | `200` com o snapshot da partida ativa |
| `GET /v1/games/{gameId}` | token de sessão de participante | `200` com o snapshot da partida |

`POST /v1/games` cria uma partida humano contra humano em `WAITING` e associa a sessão criadora como `WHITE` e `HUMAN`. Partidas `PUBLIC` não têm `entryCode` e são listadas no lobby enquanto aguardam o segundo jogador. Partidas `PRIVATE` não aparecem no lobby e recebem um `entryCode` opaco de seis caracteres para compartilhamento externo. `join` só aceita partida `WAITING` e atribui `BLACK` ao segundo guest. Para uma partida `PRIVATE`, o código é obrigatório e deve coincidir com o `entryCode` da partida; sua ausência retorna `ENTRY_CODE_REQUIRED` e um valor incorreto retorna `INVALID_ENTRY_CODE`. O corpo do `join` de uma partida `PUBLIC` não contém `entryCode`. A recuperação de sessão preserva a identidade necessária para consultar e retomar as partidas das quais ela participa.

`timeControl.initialTimeMs` aceita `180000`, `600000`, `3600000` ou `0`; `0` representa uma partida sem limite de tempo. `timeControl.incrementMs` deve ser `0`.

`GET /v1/games` aceita `page` a partir de `0` e `size` de `1` a `100`, com padrão `0` e `20`. Retorna somente partidas `PUBLIC` em `WAITING`, ordenadas por criação decrescente. A resposta inclui `games`, `page`, `size`, `totalElements` e `totalPages`. Cada item de `games` usa este formato:

```json
{
  "gameId": "d7fc48b7-570a-4d8d-a7ae-1bc5d20a9c13",
  "status": "WAITING",
  "visibility": "PUBLIC",
  "timeControl": {"initialTimeMs": 600000, "incrementMs": 0},
  "createdAt": "2026-10-02T15:00:00Z"
}
```

### Snapshot de partida

```json
{
  "gameId": "d7fc48b7-570a-4d8d-a7ae-1bc5d20a9c13",
  "status": "ACTIVE",
  "visibility": "PRIVATE",
  "entryCode": "G7K2XP",
  "revision": 12,
  "players": [
    {"side": "WHITE", "kind": "HUMAN"},
    {"side": "BLACK", "kind": "HUMAN"}
  ],
  "position": {"fen": "...", "sideToMove": "WHITE", "lastMoveUci": "e7e5"},
  "clock": {"whiteRemainingMs": 598321, "blackRemainingMs": 599004, "activeSide": "WHITE"},
  "drawOffer": null,
  "result": null
}
```

`status` é `WAITING`, `ACTIVE`, `FINISHED` ou `ABANDONED`. `position.lastMoveUci` é nulo antes do primeiro movimento. `clock.activeSide` é `WHITE` ou `BLACK` durante uma partida ativa e nulo quando o relógio está parado.

`visibility` é `PUBLIC` ou `PRIVATE`. `entryCode` é um código de seis caracteres em maiúsculas para partidas `PRIVATE` e é nulo para partidas `PUBLIC`.

`drawOffer` é nulo quando não há oferta pendente. Quando há, seu formato é `{"offeredBy":"WHITE"}`, com `offeredBy` igual a `WHITE` ou `BLACK`.

`result` é nulo enquanto `status` não é `FINISHED`. Quando a partida termina, seu formato é `{"outcome":"WHITE_WIN","reason":"CHECKMATE"}`. `outcome` é `WHITE_WIN`, `BLACK_WIN` ou `DRAW`; exemplos de `reason` são `CHECKMATE`, `STALEMATE`, `INSUFFICIENT_MATERIAL`, `DRAW_AGREED`, `RESIGNATION` e `TIMEOUT`.

### Erros REST

O corpo de erro é `{"code":"...","message":"...","details":{...}}`. `code` e `message` são strings obrigatórias e não nulas. `details` é um objeto obrigatório, não nulo e vazio quando não há dados adicionais. Para conflito de estado, pode conter `currentRevision` e `snapshot`; quando presentes, ambos são não nulos, e `snapshot` usa integralmente o formato descrito acima.

| Status | Códigos essenciais |
| --- | --- |
| `400` | `VALIDATION_ERROR`, `ENTRY_CODE_REQUIRED` |
| `401` | `SESSION_INVALID`, `SESSION_EXPIRED` |
| `403` | `NOT_A_PARTICIPANT`, `INVALID_ENTRY_CODE` |
| `404` | `GAME_NOT_FOUND` |
| `409` | `GAME_NOT_JOINABLE`, `INVALID_GAME_STATE`, `STALE_REVISION` |

## WebSocket

O cliente conecta em `GET /ws` com `X-Session-Token`. Após a conexão, cada mensagem é um objeto JSON com `type`, `requestId` e, quando aplicável, `gameId`. `requestId` é reproduzido na confirmação ou no erro correlato.

| Direção | Tipo | Campos adicionais |
| --- | --- | --- |
| cliente -> servidor | `game.subscribe` | `gameId` |
| cliente -> servidor | `game.move` | `gameId`, `revision`, `uci` |
| cliente -> servidor | `game.resign` | `gameId`, `revision` |
| cliente -> servidor | `game.draw.offer` | `gameId`, `revision` |
| cliente -> servidor | `game.draw.respond` | `gameId`, `revision`, `accepted` |
| servidor -> cliente | `game.state` | `requestId` opcional, `snapshot` |
| servidor -> cliente | `game.error` | `code`, `message`, `currentRevision`, `snapshot` |

Exemplo de movimento:

```json
{"type":"game.move","requestId":"a2","gameId":"d7fc48b7-570a-4d8d-a7ae-1bc5d20a9c13","revision":12,"uci":"e2e4"}
```

Depois de `game.subscribe`, o servidor envia imediatamente `game.state`. Após toda transição aceita, publica o novo snapshot para os participantes somente depois da persistência da revisão. O servidor rejeita uma ação com `UNAUTHENTICATED`, `NOT_A_PARTICIPANT`, `GAME_NOT_FOUND`, `INVALID_GAME_STATE`, `STALE_REVISION`, `NOT_YOUR_TURN` ou `ILLEGAL_MOVE`; a rejeição não altera a partida.
