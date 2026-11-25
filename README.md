# chess-backend

Backend Spring Boot do projeto de xadrez online.

## Execução local

Use Docker com Compose v2. Crie `.env` se ainda não existir e preencha `POSTGRES_PASSWORD` com um segredo local, que pode ser gerado com `openssl rand -hex 32`.

```bash
test -e .env || (umask 077; cp .env.example .env)
```

```bash
docker compose up -d --build --wait --wait-timeout 120
curl -fsS "http://$(docker compose port backend 8080)/actuator/health"
```

O backend atende em `127.0.0.1` na porta `BACKEND_PORT` (padrão `8080`). O Compose configura `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` e `DATABASE_SCHEMA` para acessar `postgres:5432` pela rede do projeto. Mantenha o nome do projeto Compose para reutilizar o volume `postgres-data`. As variáveis `POSTGRES_*` inicializam um volume vazio. Para trocar credenciais em um banco existente, altere também a credencial no PostgreSQL.

O Compose aguarda o PostgreSQL aceitar conexões TCP. Na inicialização do backend, o Flyway aplica as migrations ao schema `DATABASE_SCHEMA` (padrão `chess`) e o Hibernate valida o resultado. A verificação HTTP de saúde inclui a conexão ao banco.

Para executar o backend fora do Compose, exporte as variáveis `DATABASE_*` com os dados de um PostgreSQL 17 acessível e use `mvn spring-boot:run`. O Maven requer Java 21 e Maven 3.9 ou superior. Os testes de integração usam Docker via Testcontainers.

```bash
mvn verify
```

## Smoke do container

Execute na raiz do repositório em Bash com `curl`, `jq` e `openssl`. O smoke usa projeto, credenciais e volume descartáveis e uma porta de host dinâmica.

```bash
(
  set -euo pipefail
  project="xadrez-smoke-$(openssl rand -hex 8)"
  POSTGRES_PASSWORD=$(openssl rand -hex 32)
  export POSTGRES_DB=chess POSTGRES_USER=chess POSTGRES_PASSWORD DATABASE_SCHEMA=chess BACKEND_PORT=0
  export BACKEND_IMAGE="chess-backend:$project"
  compose=(docker compose -f compose.yaml --env-file .env.example -p "$project")
  trap '"${compose[@]}" down --volumes' EXIT

  "${compose[@]}" up -d --build --wait --wait-timeout 120
  test "$("${compose[@]}" exec -T backend id -u)" -gt 0
  base_url="http://$("${compose[@]}" port backend 8080)"
  token=$(curl -fsS -X POST "$base_url/v1/sessions" | jq -er '.recoveryToken // empty')
  game_id=$(curl -fsS -X POST "$base_url/v1/games" \
    -H "X-Session-Token: $token" -H 'Content-Type: application/json' \
    -d '{"visibility":"PUBLIC","timeControl":{"initialTimeMs":0,"incrementMs":0}}' \
    | jq -er '.gameId // empty')

  "${compose[@]}" down
  "${compose[@]}" up -d --no-build --wait --wait-timeout 120
  base_url="http://$("${compose[@]}" port backend 8080)"
  curl -fsS -H "X-Session-Token: $token" "$base_url/v1/games/$game_id" \
    | jq -e --arg game_id "$game_id" '.gameId == $game_id and .status == "WAITING"'
)
```

## Rollback

Antes de atualizar, separe o token e o ID de uma sessão que estará válida na restauração e o ID de uma partida dessa sessão. Suspenda as escritas e execute os blocos no mesmo shell Bash, a partir da raiz do repositório. Use o projeto Compose da instalação para salvar a imagem e fazer o dump. Prossiga com a atualização depois que o backup terminar com êxito.

```bash
previous_image="chess-backend:rollback-$(openssl rand -hex 8)"
backup_dir=$(mktemp -d)
(
  set -euo pipefail
  umask 077
  postgres_db=$(docker compose exec -T postgres printenv POSTGRES_DB)
  postgres_user=$(docker compose exec -T postgres printenv POSTGRES_USER)
  database_schema=$(docker compose exec -T backend printenv DATABASE_SCHEMA)
  restore_password=$(openssl rand -hex 32)
  printf 'POSTGRES_DB=%q\nPOSTGRES_USER=%q\nDATABASE_SCHEMA=%q\nPOSTGRES_PASSWORD=%q\n' \
    "$postgres_db" "$postgres_user" "$database_schema" "$restore_password" > "$backup_dir/restore.env.bash"
  docker image tag "$(docker inspect --format '{{.Image}}' "$(docker compose ps -q backend)")" "$previous_image"
  docker compose stop backend
  docker compose exec -T postgres sh -c 'pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > "$backup_dir/chess.dump"
  docker compose exec -T postgres pg_restore --list < "$backup_dir/chess.dump" > /dev/null
)
```

Se o schema atual passar na validação do Flyway e for compatível com a imagem anterior, recrie somente o backend. Mantenha essa tag em `.env` como `BACKEND_IMAGE` para subidas posteriores com `--no-build`.

```bash
BACKEND_IMAGE="$previous_image" docker compose up -d --wait --wait-timeout 120 \
  --no-deps --no-build --pull never backend
```

Migrations podem bloquear tabelas, alterar dados e impedir que a imagem anterior inicie. Em caso de incompatibilidade, interrompa as escritas e restaure o dump em um projeto com volume novo. Use o banco, o usuário e o schema salvos no backup e a senha gerada para o volume novo. Preserve o volume original para recuperar dados gravados depois do dump. Redirecione o tráfego somente após confirmar a recuperação da sessão e da partida com os IDs anotados.

```bash
(
  set -euo pipefail
  source "$backup_dir/restore.env.bash"
  export POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD DATABASE_SCHEMA
  export BACKEND_IMAGE="$previous_image" BACKEND_PORT=0
  restore_project="xadrez-restore-$(openssl rand -hex 8)"
  restore=(docker compose -f compose.yaml --env-file /dev/null -p "$restore_project")
  read -rp 'ID da sessão do backup: ' session_id
  read -rsp 'Token da sessão do backup: ' token
  printf '\n'
  read -rp 'ID da partida do backup: ' game_id

  "${restore[@]}" up -d --wait --wait-timeout 120 postgres
  "${restore[@]}" exec -T postgres sh -c 'pg_restore --exit-on-error -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < "$backup_dir/chess.dump"
  "${restore[@]}" up -d --wait --wait-timeout 120 --no-deps --no-build --pull never backend
  base_url="http://$("${restore[@]}" port backend 8080)"
  jq -n --arg token "$token" '{recoveryToken: $token}' \
    | curl -fsS -H 'Content-Type: application/json' --data-binary @- "$base_url/v1/sessions/recover" \
    | jq -e --arg session_id "$session_id" '.sessionId == $session_id' > /dev/null
  curl -fsS -H "X-Session-Token: $token" "$base_url/v1/games/$game_id" \
    | jq -e --arg game_id "$game_id" '.gameId == $game_id' > /dev/null
  printf '%s %s\n' "$restore_project" "$base_url"
)
```
