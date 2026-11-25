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

Antes de atualizar, guarde a imagem em execução, suspenda as escritas e faça um dump. Execute os blocos no mesmo shell Bash e use o projeto Compose da instalação para salvar a imagem e o dump. Guarde a configuração de banco e schema usada no backup. Prossiga com a atualização depois que todos os comandos tiverem êxito.

```bash
previous_image="chess-backend:rollback-$(openssl rand -hex 8)"
backup_dir=$(mktemp -d)
docker image tag "$(docker inspect --format '{{.Image}}' "$(docker compose ps -q backend)")" "$previous_image" &&
  docker compose stop backend &&
  docker compose exec -T postgres sh -c 'pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > "$backup_dir/chess.dump" &&
  docker compose exec -T postgres pg_restore --list < "$backup_dir/chess.dump" > /dev/null
```

Se o schema atual passar na validação do Flyway e for compatível com a imagem anterior, recrie somente o backend. Mantenha essa tag em `.env` como `BACKEND_IMAGE` para subidas posteriores com `--no-build`.

```bash
BACKEND_IMAGE="$previous_image" docker compose up -d --wait --wait-timeout 120 \
  --no-deps --no-build --pull never backend
```

Migrations podem bloquear tabelas, alterar dados e impedir que a imagem anterior inicie. Em caso de incompatibilidade, interrompa as escritas e restaure o dump em um projeto com volume novo usando a configuração de banco e schema do backup. O dump contém o estado no momento da cópia. Valide o ambiente restaurado antes de redirecionar o tráfego e preserve o volume original para recuperar dados gravados depois do dump.

```bash
restore=(docker compose -p "xadrez-restore-$(openssl rand -hex 8)")
"${restore[@]}" up -d --wait --wait-timeout 120 postgres &&
  "${restore[@]}" exec -T postgres sh -c 'pg_restore --exit-on-error -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < "$backup_dir/chess.dump" &&
  BACKEND_IMAGE="$previous_image" BACKEND_PORT=0 "${restore[@]}" up -d --wait --wait-timeout 120 \
    --no-deps --no-build --pull never backend &&
  curl -fsS "http://$("${restore[@]}" port backend 8080)/actuator/health"
```
