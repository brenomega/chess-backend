# chess-backend

Backend Spring Boot do projeto de xadrez online.

Requer Java 21, Maven 3.9 ou superior e PostgreSQL 17.

## Banco de dados

Copie `.env.example` para `.env` e defina um valor local para `POSTGRES_PASSWORD`.

```bash
cp .env.example .env
set -a
source .env
set +a
docker compose up -d
export DATABASE_URL="jdbc:postgresql://localhost:${POSTGRES_PORT}/${POSTGRES_DB}"
export DATABASE_USERNAME="$POSTGRES_USER"
export DATABASE_PASSWORD="$POSTGRES_PASSWORD"
```

Ao iniciar a aplicacao, o Flyway aplica as migrations em `src/main/resources/db/migration`. O schema e definido por `DATABASE_SCHEMA`, que assume `chess` quando ausente. O Hibernate somente valida o schema, sem gerar DDL.

```bash
mvn spring-boot:run
```

```bash
mvn verify
```
