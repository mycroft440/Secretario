# CallGuard AI

MVP Android de triagem local de chamadas com FunctionGemma 270M. A revisão formal **Crítico → Executor** foi concluída com estado global **SATISFEITA**; veja [`CRITIC_REVIEW.md`](CRITIC_REVIEW.md).

## Interface de demonstração solicitada

Os três registros iniciais são **DEMO** e aparecem marcados assim no app:

- `389393939` — operadora — **BLOQUEADO (DEMO)**
- `838383898` — novo número do Roberto — **PERMITIDA (DEMO)**
- `838383893` — robô/ligação muda — **BLOQUEADO (DEMO)**

Em “Ligações reais”, o exemplo do Roberto mostra:

`preciso falar com você troquei de número sou roberto`

Esses números **não fazem parte da política real de bloqueio**.

## O que o MVP realmente implementa

- Android `CallScreeningService` e solicitação de `ROLE_CALL_SCREENING`;
- resposta rápida/conservadora: número desconhecido não é bloqueado sem evidência determinística;
- compatibilidade do screening a partir do Android 10/API 29, com o sinal de verificação de número usado somente no API 30+;
- histórico local cifrado com AES-GCM e chave do Android Keystore;
- app sem permissão `INTERNET`, backup e device-transfer de dados privados desativados;
- exclusão destrutiva do histórico, ciphertext legado e chave AES pelo usuário;
- LiteRT-LM 0.16.1 + FunctionGemma em modelo `.litertlm` importado pelo seletor de arquivos;
- tool calling **manual** com `allowCall`, `blockCall`, `askForClarification`;
- validação determinística de categoria, confiança finita e cardinalidade antes de aceitar uma decisão do modelo;
- fallback GPU → CPU;
- instalação atômica do modelo, SHA-256 e trust allowlist;
- caminho de produção recalcula o SHA-256 do arquivo real antes de carregá-lo;
- nenhum modelo marcado como autorizado para produção até existir uma release CallGuard avaliada;
- laboratório para digitar transcrições e testar decisões;
- dataset seed adversarial + validação em CI;
- contrato explícito para uma futura ponte PCM bidirecional;
- testes, lint e APK exclusivamente no GitHub Actions.

## Limitação central

O `CallScreeningService` público permite **permitir, silenciar ou rejeitar** uma chamada, mas não entrega a um APK comum o áudio bidirecional PCM da chamada celular da operadora. Também não existe um segundo `respondToCall` para silenciar primeiro e depois reativar o toque da mesma ligação.

Portanto, a experiência final “IA atende em silêncio, conversa 100% offline e só depois faz a chamada celular convencional tocar” precisa de **integração OEM/privilegiada**, rota **VoIP/SIP** controlada pelo app, ou uma futura API do Android. O MVP não finge que essa ponte já existe.

## Voz offline planejada

Candidatos iniciais para benchmark quando a camada de áudio for integrada:

- VAD: Silero VAD;
- STT: whisper.cpp multilingual Base Q5_1 (~57 MB);
- TTS: primeiro tentar voz Android cuja `Voice.isNetworkConnectionRequired` seja `false`;
- intenção/ação: FunctionGemma 270M fine-tuned e quantizado.

Transcrição vazia **não** é tratada como prova de silêncio; silêncio precisa de evidência do VAD.

## Modelo FunctionGemma

Arquivo local esperado:

`functiongemma-callguard.litertlm`

O APK não possui Internet. O modelo é obtido externamente e importado pelo seletor de arquivos. A instalação preserva o modelo anterior até concluir, calcula SHA-256 e registra o estado de confiança. Antes de qualquer inicialização de **produção**, o app recalcula o SHA-256 do arquivo que será executado e exige correspondência com a allowlist compilada. A allowlist atual está vazia porque ainda não existe um fine-tune oficial aprovado.

## Tamanho do pacote

O modelo FunctionGemma fica fora do APK, mas isso **não significa que o APK seja pequeno**: o runtime LiteRT-LM e suas bibliotecas nativas têm custo relevante. O artifact debug universal do run #107 ficou em aproximadamente **107 MiB**.

A inspeção desse artifact mostrou aproximadamente **45 MiB** em bibliotecas nativas para `arm64-v8a` + `x86_64` e cerca de **61 MiB** em DEX/dependências. Esses números são de um APK debug universal e não representam ainda um pacote final otimizado.

Antes de release, o tamanho deverá ser medido novamente usando **Android App Bundle/ABI splits**, shrink/minificação com **R8** quando compatível com o LiteRT-LM e medição do download/instalação por arquitetura. O projeto não promete um tamanho final até essa medição existir.

## Build — somente GitHub Actions

**Não compilar localmente.**

A branch revisada usa:

- Android Gradle Plugin 9.3.1;
- Gradle 9.5.0;
- JDK 21 para executar Gradle/testes, exigido pelo bytecode atual do LiteRT-LM;
- bytecode do código do app mantido em Java 17 (`sourceCompatibility`/`targetCompatibility`);
- compile SDK 37;
- target SDK 36, mantido deliberadamente até teste das mudanças de comportamento do Android 17 em hardware/emulador;
- Compose BOM `2026.08.00`;
- LiteRT-LM Android `0.16.1` fixado;
- coroutines `1.11.0` fixado.

O workflow:

1. instala SDK/API 37 (`platforms;android-37.0`);
2. valida o dataset seed;
3. executa testes unitários;
4. executa Android lint;
5. gera o APK debug;
6. publica APK e relatórios como artifacts.

A última mudança funcional/copy foi validada pelo **run #115**, com dataset, 15 testes, lint, APK e uploads verdes.

## Próximos passos

Veja [`PLAN.md`](PLAN.md) para arquitetura/fases, [`training/README.md`](training/README.md) para fine-tuning/gates de avaliação, [`PRIVACY.md`](PRIVACY.md) para requisitos de dados e [`THIRD_PARTY.md`](THIRD_PARTY.md) para licenças/termos de terceiros.
