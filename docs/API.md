# Contrato REST e WebSocket

O cliente envia JSON em UTF-8. Identificadores são UUIDs, instantes usam ISO-8601 UTC e durações usam milissegundos. O valor opaco `recoveryToken` retornado pela sessão é enviado em `X-Session-Token` para resolver a identidade guest; não há autenticação global.

## REST

| Operação | Requisição | Resposta de sucesso |
| --- | --- | --- |
| `POST /v1/sessions` | corpo vazio | `201` com `sessionId`, `recoveryToken` e `expiresAt` |
| `POST /v1/sessions/recover` | `{"recoveryToken":"..."}` | `200` com `sessionId`, `recoveryToken` e `expiresAt` |
| `GET /v1/games?page=0&size=20` | token de sessão | `200` com página de `games`, contendo `gameId`, `status`, `visibility`, `timeControl` e `createdAt` |
| `POST /v1/games` | token de sessão; `{"visibility":"PUBLIC|PRIVATE","timeControl":{"initialTimeMs":600000,"incrementMs":0},"mode":"HUMAN_VS_HUMAN|HUMAN_VS_AI|AI_VS_AI","side":"WHITE|BLACK"}` | `201` com o snapshot da partida |
| `POST /v1/games/join` | token de sessão; `{"entryCode":"G7K2XP"}` | `200` com o snapshot da partida `PRIVATE` ativa, incluindo `gameId` |
| `POST /v1/games/{gameId}/join` | token de sessão; corpo vazio para `PUBLIC` ou `{"entryCode":"G7K2XP"}` para `PRIVATE` | `200` com o snapshot da partida ativa |
| `GET /v1/games/{gameId}` | token de participante ou de observador de `AI_VS_AI` | `200` com o snapshot da partida |

Em `HUMAN_VS_HUMAN`, partidas `PUBLIC` não têm `entryCode` e são listadas no lobby enquanto aguardam o segundo jogador. Partidas `PRIVATE` não aparecem no lobby e recebem um `entryCode` opaco de seis caracteres para compartilhamento externo. `POST /v1/games/join` localiza somente uma partida `PRIVATE` em `WAITING` pelo código e devolve o snapshot com `gameId`. Código ausente retorna `ENTRY_CODE_REQUIRED`; código malformado, inexistente ou que não identifica mais uma partida privada em espera retorna `INVALID_ENTRY_CODE`, sem revelar outra partida. O fluxo público permanece em `POST /v1/games/{gameId}/join`, com corpo vazio. Essa rota por `gameId` também preserva o contrato anterior para partidas `PRIVATE`, exigindo o código correspondente. A recuperação de sessão preserva a identidade necessária para consultar e retomar as partidas das quais ela participa.

A entrada serializa a ocupação de `BLACK` na linha da partida no PostgreSQL e valida o estado sob a mesma trava. Depois que a vaga é ocupada, a rota por `gameId` retorna `409 GAME_NOT_JOINABLE` para entradas válidas quanto ao código, e a rota por código retorna `403 INVALID_ENTRY_CODE` se o código deixou de identificar uma partida privada em espera. A entrada reutiliza a [trava de linha das ações da partida (#41)](https://github.com/brenomega/chess-backend/issues/41).

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

### Modos de partida

`mode` escolhe a composição da partida e é opcional: ausente, vale `HUMAN_VS_HUMAN`, que era o único modo antes, então clientes que não conhecem o campo continuam funcionando.

| Modo | Participantes | Estado inicial |
| --- | --- | --- |
| `HUMAN_VS_HUMAN` | criador como `WHITE`, segundo guest como `BLACK` | `WAITING` até alguém entrar |
| `HUMAN_VS_AI` | criador no lado de `side`, IA no lado oposto | `ACTIVE`, relógio correndo |
| `AI_VS_AI` | duas IAs | `ACTIVE`, relógio correndo |

Partidas com IA são sempre `PUBLIC` e sem `entryCode`. O campo `visibility` enviado é ignorado. Essas partidas não aceitam entrada de jogadores e ficam fora do lobby.

`side` escolhe o lado do humano em `HUMAN_VS_AI` e vale `WHITE` quando ausente; nos outros modos é ignorado. As brancas sempre começam, então criar uma partida com `side` igual a `BLACK` faz a IA jogar primeiro.

Qualquer sessão válida pode consultar ou assinar uma partida `AI_VS_AI`. Nos demais modos, a consulta e a inscrição são restritas aos participantes.

Quando a vez é de um participante `AI`, o Backend enfileira o trabalho para o worker e aplica o lance que ele devolver, pelas mesmas regras de um lance humano. O cliente não faz nada de diferente: recebe um `game.state` novo quando a IA joga. O contrato entre Backend e worker está em `docs/contracts/backend-ai.md` e não faz parte da API do cliente.

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

O cliente conecta em `GET /ws` com `X-Session-Token`. O upgrade exige uma sessão guest válida; token ausente, inválido ou expirado rejeita o handshake com HTTP `401`. Após a conexão, cada mensagem é um objeto JSON com `type`, `requestId` e os campos do tipo. `requestId` é obrigatório nas mensagens do cliente e deve ser uma string Unicode válida, não vazia nem composta apenas por espaços em branco, sem NUL.

| Direção | Tipo | Campos adicionais |
| --- | --- | --- |
| cliente -> servidor | `game.subscribe` | `gameId` |
| cliente -> servidor | `game.move` | `gameId`, `uci`, `revision` |
| cliente -> servidor | `game.abandon` | `gameId`, `revision` |
| cliente -> servidor | `draw.offer` | `gameId`, `revision` |
| cliente -> servidor | `draw.respond` | `gameId`, `accept`, `revision` |
| servidor -> cliente | `game.state` | `requestId` opcional, `snapshot` |
| servidor -> cliente | `game.error` | `requestId` opcional, `code`, `message` |

Exemplo de inscrição:

```json
{"type":"game.subscribe","requestId":"s1","gameId":"d7fc48b7-570a-4d8d-a7ae-1bc5d20a9c13"}
```

Após autorizar `game.subscribe`, o servidor associa a conexão à partida e responde com `game.state`.

```json
{"type":"game.state","requestId":"s1","snapshot":{"gameId":"d7fc48b7-570a-4d8d-a7ae-1bc5d20a9c13","status":"WAITING"}}
```

O campo `snapshot` sempre usa integralmente o formato da seção "Snapshot de partida"; o exemplo está abreviado apenas para destacar o envelope. Uma nova inscrição válida na mesma conexão substitui a associação anterior. A inscrição segue as permissões de observação dos modos de partida.

Após uma transição REST aceita, o servidor publica `game.state` sem `requestId` para as conexões inscritas na partida, somente depois do commit da transação persistente. Nesta versão, a transição publicada é a entrada do segundo jogador, de `WAITING` para `ACTIVE`.

Cada mensagem deve conter um único objeto JSON. JSON inválido, conteúdo excedente, campos obrigatórios ausentes ou `gameId` inválido retornam `VALIDATION_ERROR`. Tipo ainda não suportado retorna `UNSUPPORTED_MESSAGE`. Partida inexistente retorna `GAME_NOT_FOUND` e tentativa de observação sem permissão retorna `NOT_A_PARTICIPANT`. O erro correlato inclui o `requestId` recebido quando ele for uma string válida:

```json
{"type":"game.error","requestId":"s1","code":"NOT_A_PARTICIPANT","message":"Not a game participant"}
```

Essas rejeições não alteram nem associam a partida.

### Ações da partida

Novas ações só são aceitas de participantes humanos de partidas em `ACTIVE`. Observadores de `AI_VS_AI` recebem `NOT_A_PARTICIPANT`. Uma ação aceita responde com `game.state` correlacionado ao `requestId`. Após o commit de uma transição, as demais conexões inscritas recebem uma publicação de `game.state` sem `requestId`, inclusive outras conexões da sessão do emissor. As transições de uma partida são publicadas em ordem crescente de revisão.

```json
{"type":"game.move","requestId":"m1","gameId":"d7fc48b7-570a-4d8d-a7ae-1bc5d20a9c13","uci":"e2e4","revision":12}
{"type":"game.abandon","requestId":"m2","gameId":"d7fc48b7-570a-4d8d-a7ae-1bc5d20a9c13","revision":12}
{"type":"draw.offer","requestId":"m3","gameId":"d7fc48b7-570a-4d8d-a7ae-1bc5d20a9c13","revision":13}
{"type":"draw.respond","requestId":"m4","gameId":"d7fc48b7-570a-4d8d-a7ae-1bc5d20a9c13","accept":true,"revision":14}
```

`uci` usa notação UCI de quatro caracteres, com um quinto caractere (`q`, `r`, `b` ou `n`) na promoção.

`revision` é obrigatório para novas ações e deve ser um inteiro JSON de 64 bits não negativo. Envie a revisão do snapshot que motivou a ação, inclusive ao responder a uma oferta de empate. O servidor compara a revisão recebida com a persistida sob a trava da partida. Uma divergência retorna `STALE_REVISION` sem alterar a partida.

Para ações, use um `requestId` com até 128 caracteres Unicode válidos, sem NUL, e reserve cada ID para uma ação por sessão e partida. Um reenvio com o mesmo tipo, revisão e dados da ação responde ao participante com o snapshot persistido atual, mesmo após outra transição ou reinício, sem reaplicar a ação ou republicar o estado. A omissão de `revision` só é aceita no reenvio de uma ação que já tenha um recibo com a revisão omitida. Reutilizar um ID aceito em outro comando válido retorna `REQUEST_ID_CONFLICT`.

`game.abandon` encerra a partida com vitória do oponente por `RESIGNATION`. `draw.offer` registra a oferta; oferecer quando o oponente já ofereceu equivale a aceitar. Uma nova oferta do mesmo proponente com oferta pendente retorna a revisão atual com o saldo apurado, sem persistir os saldos nem publicar o snapshot. `draw.respond` com `accept` verdadeiro encerra em `DRAW` por `DRAW_AGREED`, e com `accept` falso apenas retira a oferta. Uma oferta pendente é descartada no lance seguinte.

O relógio é autoritativo no servidor: o tempo decorrido desde a última atualização é descontado do lado da vez em toda ação. Se o tempo do lado da vez chega a zero, a partida termina por `TIMEOUT` com vitória do oponente, o estado encerrado é publicado aos inscritos e a ação que chegou atrasada é recusada com `INVALID_GAME_STATE`.

### Códigos de erro do WebSocket

| Código | Quando ocorre |
| --- | --- |
| `VALIDATION_ERROR` | JSON inválido, campo obrigatório ausente, `requestId` inválido, `gameId` fora do formato UUID, `uci` fora da notação UCI ou revisão inválida ou indisponível |
| `UNSUPPORTED_MESSAGE` | `type` não previsto neste contrato |
| `SESSION_INVALID` | conexão sem sessão guest válida |
| `GAME_NOT_FOUND` | partida inexistente |
| `NOT_A_PARTICIPANT` | a sessão não joga esta partida |
| `INVALID_GAME_STATE` | partida não está `ACTIVE` |
| `NOT_YOUR_TURN` | ação de lance fora da vez |
| `STALE_REVISION` | `revision` diferente da revisão atual da partida |
| `REQUEST_ID_CONFLICT` | ID já aceito para outra ação da mesma sessão na partida |
| `ILLEGAL_MOVE` | lance ilegal na posição |
| `NO_DRAW_OFFER` | `draw.respond` sem oferta pendente do oponente |

Nenhuma rejeição altera a partida, com uma exceção deliberada: quando a recusa é o estouro do relógio, a partida é encerrada e persistida antes do erro ser enviado.
