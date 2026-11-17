# CRUD de Gastos em Java (Spring Boot) com Docker, Testes e Swagger

## O que é
API HTTP em Java 21 com Spring Boot para gerenciar gastos (CRUD) com persistência local em arquivo JSON. Inclui testes automatizados e um exemplo simples de deploy via Docker, além de documentação por Swagger/OpenAPI.

## Por que existe
Para ter um endpoint real e simples de usar e validar: criar, listar, atualizar e remover gastos, com validação básica de entrada, status HTTP coerentes e um jeito fácil de rodar localmente e em container.

## Como rodar

### Requisitos
- Java 21
- Docker (opcional para rodar em container)

### Rodar com o Java (sem Maven)
1) Compile:

`javac -d out src/Main.java`

2) Suba o servidor HTTP:

`java -cp out Main`

> O modo sem Maven usa `com.sun.net.httpserver` e é útil para demonstrar o CRUD rapidamente.

### Rodar com Docker
1) Build da imagem:

`docker build -t gastos-crud .`

2) Subir o container:

`docker run --rm -p 8080:8080 gastos-crud`

A API fica em:
- http://localhost:8080
- Swagger: http://localhost:8080/swagger-ui.html (se habilitado na configuração do Spring)

## Como funciona
- `POST /api/gastos`: cria um gasto (valida campos obrigatórios e valores).
- `GET /api/gastos`: lista gastos por período opcional (`inicio`, `fim`).
- `GET /api/gastos/{id}`: busca por ID.
- `PUT /api/gastos/{id}`: atualiza tudo com validação simples.
- `DELETE /api/gastos/{id}`: remove.

Persistência:
- Os gastos são armazenados em `data/gastos.json` no diretório do processo.
- O arquivo é lido no startup e reescrito ao final de operações que alteram o estado.

Testes:
- Há testes unitários com JUnit usando Java 21 (sem libs externas além do JUnit, quando disponível no ambiente).
- Validações e regras de negócio mínimas (datas, valores e campos obrigatórios).

Swagger:
- No caminho do projeto, a configuração do Spring expõe OpenAPI e serve a UI do Swagger.

> Observação: este repositório também inclui o modo HTTP puro com `com.sun.net.httpserver` via `src/Main.java`, para manter o requisito de build sem ferramentas externas.
