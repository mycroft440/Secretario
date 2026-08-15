# CallGuard AI

MVP Android de triagem local de chamadas com FunctionGemma 270M.

## Tela inicial solicitada

**Ligações do dia — 3**

- `389393939` — operadora — **BLOQUEADO**
- `838383898` — novo número do Roberto
- `838383893` — robô/ligação muda — **BLOQUEADO**

**Ligações reais — 1**

- `838383898` disse: `preciso falar com você troquei de número sou roberto`

## O que funciona no código

- Android `CallScreeningService`;
- solicitação de `ROLE_CALL_SCREENING`;
- política rápida para bloquear regras locais sem depender do LLM;
- histórico persistente em armazenamento privado do app;
- integração LiteRT-LM com modelo `.litertlm` instalado pelo usuário;
- ferramentas `allowCall`, `blockCall`, `askForClarification`;
- fallback GPU → CPU;
- laboratório para digitar uma transcrição e testar a decisão;
- modo demo explícito quando nenhum modelo está instalado;
- GitHub Actions para testes e APK debug.

## O que ainda NÃO é possível via API pública do app

O MVP não finge ter acesso ao áudio bidirecional de uma chamada normal da operadora. `CallScreeningService` permite responder à chamada, mas não fornece esse áudio para um APK comum. A ponte está modelada em `audio/CarrierAudioBridge.kt` para ser conectada quando uma rota suportada for escolhida.

Também não silenciamos chamadas legítimas no `CallScreeningService` esperando “reativar o toque” depois: a resposta de screening é única. Até a ponte existir, números que não batem nas regras de bloqueio são permitidos normalmente.

## FunctionGemma

O arquivo esperado é:

`functiongemma-callguard.litertlm`

Ele é armazenado no diretório privado `files/models/` e fica fora do APK. A tela “Instalar IA” permite selecionar o modelo, calcula SHA-256 e mantém o APK pequeno.

Para produção, use uma variante FunctionGemma 270M fine-tuned para as ferramentas do CallGuard e convertida/quantizada para `.litertlm`.

## Build — somente GitHub Actions

**Não compilar localmente.** O projeto foi configurado para deixar testes, lint e geração do APK exclusivamente no GitHub Actions.

A CI usa Android Gradle Plugin 9.3.1, Gradle 9.5.0, JDK 17, compile SDK 37, target SDK 36, Compose BOM 2026.06.00 e LiteRT-LM Android 0.14.0 fixado para builds reproduzíveis. O workflow executa, nesta ordem:

1. validação do dataset seed;
2. `:app:testDebugUnitTest`;
3. `:app:lintDebug`;
4. `:app:assembleDebug`;
5. upload do APK e dos relatórios como artifacts.

O projeto local deve ser usado apenas para edição, revisão estática e commits.

## Arquitetura e próximos passos

Veja [`PLAN.md`](PLAN.md) para o plano completo e [`training/README.md`](training/README.md) para o dataset/fine-tuning.
