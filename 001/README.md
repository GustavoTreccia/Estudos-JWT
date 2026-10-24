# Servico de autenticacao

Java 21+, Spring Boot 3.5, Spring Authorization Server, PostgreSQL, Flyway e SMTP.

## Arquitetura MVC REST

O codigo em `src/main/java/br/com/estudos/auth` esta separado por responsabilidade:

```text
auth/
  model/        Entidades JPA e transicoes de estado da conta
  record/       DTOs imutaveis de entrada e saida, com validacao
  repository/   Acesso ao PostgreSQL e bloqueios transacionais
  service/      Regras de cadastro, confirmacao e envio de e-mail
  controller/   Endpoints REST e tratamento HTTP de erros
  config/       Configuracao OAuth2, JWT, seguranca e propriedades
  exception/    Excecoes de negocio
```

Os controllers delegam as regras de negocio aos services, que usam repositories
para persistir models. Os records representam os contratos JSON da API; entidades
JPA nunca sao devolvidas diretamente. A representacao deste MVC REST e JSON,
sem uma camada de templates para as operacoes de cadastro.

Nao ha Lombok: os models usam getters explicitos. Alteracoes sensiveis no estado
da conta passam por metodos de negocio, em vez de setters publicos irrestritos.
Os DTOs usam os accessors gerados pelo proprio Java para records.

## O que esta implementado

- Cadastro local com senha BCrypt e login bloqueado ate a confirmacao do e-mail.
- Codigo de seis digitos enviado exclusivamente por e-mail, valido por 10 minutos.
- Codigo armazenado como HMAC-SHA256, vinculado ao e-mail e protegido por segredo externo.
- Cinco tentativas incorretas bloqueiam o codigo; reenvio permitido a cada 60 segundos.
- Reenvio substitui o codigo anterior e reinicia as tentativas.
- OAuth2 Authorization Code com PKCE S256 obrigatorio, consentimento e OpenID Connect.
- Access token JWT RS256 de 15 minutos; refresh token de 8 horas, com rotacao.
- Chaves RSA persistentes externas, discovery e publicacao somente das chaves publicas.
- Contas, autorizacoes, consentimentos, clientes e sessoes armazenados no PostgreSQL.
- API protegida valida assinatura, expiracao, emissor, audiencia e escopo do JWT.
- Erros REST no formato Problem Details; falha SMTP retorna 503 e desfaz a alteracao no banco.

O cliente configurado e **confidencial**, para backend/BFF. O segredo do cliente nunca
deve ser colocado em JavaScript de navegador ou aplicativos mobile. Nao existe endpoint
que troque diretamente e-mail/senha por JWT: o login utiliza o fluxo padrao OAuth2.

## Executar localmente com Docker

Requisitos: Docker com Compose e OpenSSL.

```sh
sh scripts/generate-dev-secrets.sh
docker compose up --build
```

O script cria `.env` e um par RSA de 3072 bits em `.local/`, com permissoes restritas.
Nao sobrescreve arquivos existentes. Esses caminhos sao ignorados pelo Git e pelo build
Docker. Guarde as chaves: troca-las invalida a verificacao de tokens existentes.

- Servico: <http://localhost:8080>
- Caixa de e-mail Mailpit: <http://localhost:8025>
- PostgreSQL: `localhost:5432`, banco `auth`, usuario `auth`.
- Login: <http://localhost:8080/login>
- Discovery: <http://localhost:8080/.well-known/openid-configuration>
- JWKS: <http://localhost:8080/oauth2/jwks>

O Compose e exclusivamente local: portas vinculadas a loopback, Mailpit sem entrega
externa e cookie sem `Secure` para permitir HTTP. PostgreSQL usa volume persistente.
Nao use `docker compose down -v` se quiser manter os dados.

### Executar o Java fora do Docker

Com Java 21+ e Maven instalados:

```sh
sh scripts/generate-dev-secrets.sh # somente se ainda nao foi executado
docker compose up -d postgres mailpit
set -a
. ./.env
set +a
export COOKIE_SECURE=false
mvn spring-boot:run
```

## Cadastro e confirmacao

```sh
curl -i http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"pessoa@example.com","password":"uma-senha-forte-123"}'
```

Retorna `202`. Abra o Mailpit e copie o codigo recebido:

```sh
curl -i http://localhost:8080/api/auth/confirm \
  -H 'Content-Type: application/json' \
  -d '{"email":"pessoa@example.com","code":"123456"}'
```

Para solicitar outro codigo:

```sh
curl -i http://localhost:8080/api/auth/resend \
  -H 'Content-Type: application/json' \
  -d '{"email":"pessoa@example.com"}'
```

| Operacao | Resultado |
|---|---|
| Cadastro / reenvio elegivel | `202` com mensagem generica, sem codigo |
| Confirmacao correta | `200`; conta habilitada |
| Dados invalidos / codigo invalido, usado, expirado ou bloqueado | `400` |
| Reenvio antes de 60 segundos | `429`, com `Retry-After: 60` |
| SMTP indisponivel | `503`; transacao revertida |
| Cadastro concorrente conflitante | `409`; repetir a requisicao |

E-mails sao normalizados para minusculas. Senhas exigem 12 a 72 caracteres e no
maximo 72 bytes UTF-8, respeitando o limite do BCrypt. Cadastrar novamente um e-mail
existente nao troca a senha nem reenvia o codigo. E-mails inexistentes ou ja confirmados
recebem resposta generica no reenvio. A confirmacao nao cria sessao nem emite tokens.

## Login OAuth2 / OpenID Connect

1. Seu backend/BFF gera valores aleatorios de `state`, `nonce` e `code_verifier`.
   O verifier deve ter 43 a 128 caracteres permitidos pelo PKCE.
2. Calcule `code_challenge = BASE64URL(SHA256(code_verifier))`, sem padding.
3. Redirecione o navegador para:

```text
http://localhost:8080/oauth2/authorize?response_type=code&client_id=web-client&redirect_uri=http%3A%2F%2F127.0.0.1%3A8081%2Fcallback&scope=openid%20profile%20email&state=SEU_STATE&nonce=SEU_NONCE&code_challenge=SEU_CHALLENGE&code_challenge_method=S256
```

4. O usuario entra com e-mail e senha e aprova o consentimento.
5. O servidor redireciona para `http://127.0.0.1:8081/callback?code=...&state=...`.
   Esse callback **pertence ao seu cliente**; nao ha frontend/BFF neste repositorio.
   Valide o `state` antes de continuar.
6. Seu backend troca o codigo, uma unica vez, por tokens:

```sh
# Carregue .env no shell como no exemplo anterior.
curl http://localhost:8080/oauth2/token \
  -u "web-client:$OAUTH_CLIENT_SECRET" \
  --data-urlencode 'grant_type=authorization_code' \
  --data-urlencode 'code=CODIGO_DO_CALLBACK' \
  --data-urlencode 'redirect_uri=http://127.0.0.1:8081/callback' \
  --data-urlencode 'code_verifier=SEU_VERIFIER'
```

Valide assinatura, issuer, audiencia do cliente e nonce do `id_token` no cliente.
Use apenas o `access_token`, nao o `id_token`, para acessar a API:

```sh
curl http://localhost:8080/api/me -H "Authorization: Bearer $ACCESS_TOKEN"
curl http://localhost:8080/userinfo -H "Authorization: Bearer $ACCESS_TOKEN"
```

O endpoint `/api/me` exige o escopo `profile` e retorna `{"id":"UUID","email":"..."}`.
Nao aceita autenticacao apenas por cookie de sessao.

### Renovar e revogar

```sh
curl http://localhost:8080/oauth2/token \
  -u "web-client:$OAUTH_CLIENT_SECRET" \
  --data-urlencode 'grant_type=refresh_token' \
  --data-urlencode "refresh_token=$REFRESH_TOKEN"

curl http://localhost:8080/oauth2/revoke \
  -u "web-client:$OAUTH_CLIENT_SECRET" \
  --data-urlencode "token=$REFRESH_TOKEN" \
  --data-urlencode 'token_type_hint=refresh_token'
```

A cada renovacao, salve o novo refresh token: o anterior deixa de funcionar.
Reutilizar um authorization code e rejeitado e pode invalidar os tokens daquela
autorizacao. JWTs validados localmente continuam aceitos ate expirar, mesmo apos
revogacao ou logout. Revogacao imediata de access tokens exige introspeccao ou
uma politica adicional de bloqueio; nao esta implementada nesta API.

## Configuracao por ambiente

| Variavel | Padrao / finalidade |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/auth` |
| `DB_USERNAME` | `auth` |
| `DB_PASSWORD` | Obrigatoria |
| `AUTH_ISSUER` | `http://localhost:8080`; URL publica estavel do servidor |
| `AUTH_AUDIENCE` | `auth-api`; audiencia dos access tokens |
| `OAUTH_CLIENT_ID` | `web-client` |
| `OAUTH_CLIENT_SECRET` | Obrigatoria; use 64 caracteres hex aleatorios |
| `OAUTH_REDIRECT_URI` | `http://127.0.0.1:8081/callback`; correspondencia exata |
| `VERIFICATION_CODE_SECRET` | Obrigatoria; pelo menos 32 caracteres aleatorios |
| `JWT_PRIVATE_KEY` | `file:./.local/private.pem`; PEM PKCS#8 |
| `JWT_PUBLIC_KEY` | `file:./.local/public.pem`; PEM X.509 |
| `SMTP_HOST` / `SMTP_PORT` | `localhost` / `1025`; no Compose, host `mailpit` |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | Credenciais do provedor, quando necessarias |
| `SMTP_AUTH` | `false`; use `true` com SMTP autenticado |
| `SMTP_STARTTLS` | `false`; use `true` para exigir STARTTLS, normalmente porta 587 |
| `MAIL_FROM` | `no-reply@example.test`; ajuste para remetente autorizado |
| `COOKIE_SECURE` | `true` na aplicacao; `false` somente no Compose local |
| `SERVER_PORT` | `8080` |

Nunca coloque credenciais reais no codigo ou no Git. Em producao, use um gerenciador
de segredos e monte as chaves somente para leitura. Configure HTTPS no issuer e no
callback e mantenha cookies Secure. Cabecalhos de proxy nao sao confiados automaticamente:
configure-os somente quando o proxy de entrada for controlado.

Antes de exposicao publica, configure limite de requisicoes no gateway por IP e
identidade para cadastro, confirmacao, reenvio, login e endpoints OAuth2. O limite de
tentativas implementado aqui protege o codigo de confirmacao; nao substitui controle
de abuso distribuido nem protecao de brute force no login. Nao ha CORS aberto:
o fluxo previsto utiliza redirecionamentos do navegador e um backend/BFF.

O envio SMTP e sincrono, com timeout e rollback de banco em caso de falha. SMTP nao
participa da transacao PostgreSQL: uma falha de commit posterior ao envio pode deixar
um e-mail com codigo inutilizavel. O usuario pode solicitar reenvio. Para requisitos
de entrega mais fortes, adote outbox transacional e processamento assincrono.

## PostgreSQL e testes

As migracoes Flyway usam tipos nativos PostgreSQL (`UUID`, `BYTEA`, `TEXT` e timestamps
com fuso). Hibernate somente valida o schema; nao cria nem altera tabelas.
Os campos de principal aceitam o mesmo limite de 254 caracteres do e-mail.
As tabelas OAuth2 usam `TEXT`, sem large objects/OIDs, para os dados serializados pelo
Spring Authorization Server.

```sh
mvn verify
```

Os testes iniciam PostgreSQL 17 via Testcontainers e exigem Docker disponivel.
Nao usam H2. Tambem testam envio SMTP real contra GreenMail local, sem enviar e-mails
para a internet. Alternativamente, utilize um banco PostgreSQL **exclusivo para testes**:

```sh
export TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/auth_test
export TEST_DATABASE_USERNAME=auth_test
export TEST_DATABASE_PASSWORD='senha-do-banco-de-testes'
mvn verify
```

O banco precisa existir; as migracoes sao aplicadas automaticamente. Nunca aponte
os testes para producao. Cobertura: validacao de entradas, hashes, bloqueio de login,
confirmacao, expiracao exata, cinco tentativas, reenvio, rollback SMTP, tamanho maximo
de e-mail, sessoes JDBC, discovery/JWKS, validacao JWT, consentimento, PKCE, troca de
codigo, UserInfo, rotacao de refresh token e revogacao.
