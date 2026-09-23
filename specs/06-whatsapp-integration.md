# Spec 06 — Integração com WhatsApp (Webhook)

## Objetivo
Permitir que o RAG seja testado/consumido via WhatsApp, reaproveitando o
`RagQueryService` (spec 02) já existente, através de um webhook HTTP
compatível com o Twilio WhatsApp Sandbox (gratuito para desenvolvimento,
sem exigir verificação de conta comercial no Meta Business).

Este módulo é **opcional e desacoplado**: nenhuma outra spec depende dele.
Ele apenas traduz o payload do provedor de mensageria para uma chamada de
`RagQueryService.ask(sessionId, question)` e formata a resposta de volta
no formato esperado pelo provedor (TwiML).

## Requisitos Funcionais
- RF01: O sistema deve expor `POST /webhook/whatsapp/twilio`, aceitando
  `application/x-www-form-urlencoded` (formato padrão dos webhooks do
  Twilio), com os campos `From` (ex: `whatsapp:+5511999999999`) e `Body`
  (texto da mensagem recebida).
- RF02: O número do remetente (`From`, sem o prefixo `whatsapp:`) deve ser
  usado como `sessionId` do `RagQueryService`, preservando o histórico de
  conversa por contato (RF07 da spec 02).
- RF03: A resposta deve ser devolvida como XML TwiML
  (`Content-Type: text/xml`), no formato:
  ```xml
  <?xml version="1.0" encoding="UTF-8"?>
  <Response><Message>{resposta}</Message></Response>
  ```
- RF04: Caracteres especiais de XML na resposta (`&`, `<`, `>`, `"`, `'`)
  devem ser escapados antes de serem inseridos no corpo do TwiML.
- RF05: Respostas muito longas devem ser truncadas para no máximo 1500
  caracteres (limite prático de mensagens de sessão do WhatsApp via
  Twilio), com um sufixo indicando o truncamento.
- RF06: Se `Body` estiver vazio ou ausente, o sistema deve responder com
  uma mensagem padrão pedindo para o usuário reformular a pergunta, sem
  chamar o `RagQueryService`.

## Requisitos Não-Funcionais
- RNF01: O endpoint não deve exigir autenticação adicional (o Twilio
  Sandbox não oferece assinatura de payload configurável de forma trivial
  em ambiente gratuito de teste); isso é aceitável apenas para uso local
  de desenvolvimento/teste, nunca para produção sem validação de
  assinatura do Twilio.
- RNF02: O endpoint deve responder em menos de 15s (mesmo limite da spec
  02), pois o Twilio expira o webhook após um timeout curto.

## Contrato (Interface HTTP)

### POST /webhook/whatsapp/twilio
**Request** (`application/x-www-form-urlencoded`)
```
From=whatsapp:+5511999999999&Body=Qual e a garantia do produto X?
```
**Response 200** (`text/xml`)
```xml
<?xml version="1.0" encoding="UTF-8"?>
<Response><Message>A garantia do produto X e de 12 meses...</Message></Response>
```

## Critérios de Aceite
- CA01: Uma requisição com `Body` preenchido deve retornar TwiML contendo
  a resposta do `RagQueryService` para a pergunta enviada.
- CA02: Duas requisições consecutivas com o mesmo `From` devem preservar
  o histórico de conversa (mesma sessão) no `RagQueryService`.
- CA03: Uma requisição sem `Body` (ou em branco) deve retornar TwiML com
  uma mensagem padrão, sem chamar `RagQueryService`.
- CA04: Uma resposta gerada com caracteres especiais de XML deve ser
  escapada corretamente no TwiML retornado (validável via parse do XML).

## Testes Esperados
- Testes de integração com `MockMvc` cobrindo CA01–CA04, usando os fakes
  determinísticos já existentes em `testsupport` (mesmo padrão da spec 02).

## Como testar gratuitamente (fora do código)
1. Rodar a aplicação localmente (`mvn spring-boot:run`).
2. Expor a porta 8080 publicamente com um túnel gratuito (ex: `ngrok http
   8080`), obtendo uma URL HTTPS temporária.
3. Ativar o Twilio WhatsApp Sandbox (gratuito, conta Twilio free trial) e
   configurar o webhook "WHEN A MESSAGE COMES IN" para
   `https://<url-do-ngrok>/webhook/whatsapp/twilio`.
4. Enviar a mensagem de "join <código-sandbox>" pelo WhatsApp pessoal para
   o número do sandbox, e então enviar perguntas normalmente.
