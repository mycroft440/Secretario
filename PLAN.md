# CallGuard AI — plano técnico após revisão Crítico → Executor

## Objetivo

Experiência desejada para **números fora dos contatos**:

1. a chamada chega sem interromper o usuário;
2. um agente de voz local pergunta nome e motivo;
3. VAD/STT local produzem evidência e transcrição;
4. FunctionGemma 270M propõe `allowCall`, `blockCall` ou `askForClarification`;
5. o aplicativo valida a proposta por regras determinísticas;
6. spam/robô/golpe confirmado é encerrado;
7. chamada legítima é apresentada ao usuário com o motivo.

## Limite e oportunidade do Android público

Um APK comum com `CallScreeningService` consegue permitir, silenciar ou rejeitar uma chamada e deve responder em até 5 segundos. Ele **não recebe o áudio bidirecional PCM da chamada celular da operadora** e não possui uma segunda resposta para silenciar uma chamada e, depois, religar o toque da mesma chamada.

Além disso, sem `READ_CONTACTS`, o Android entrega ao screening justamente chamadas cujo número não está nos contatos — o comportamento desejado aqui. Chamadas com apresentação restrita/oculta/indisponível não são entregues ao `CallScreeningService`.

No Android 17/API 37 existe uma nova rota pública para **Connections externas**: `Connection.setAudioProcessing(Call.AUDIO_PROCESSING_USE_CASE_CALL_SCREENING)` coloca a chamada em `STATE_AUDIO_PROCESSING`, e `setSimulatedRinging()` pode apresentá-la ao usuário depois. As duas operações exigem `PROPERTY_IS_EXTERNAL_CALL`; elas não tornam uma chamada SIM/PSTN comum externa nem fornecem PCM por si só.

Consequência: **APK público + chamada SIM convencional + conversa bidirecional da IA + 100% offline ainda não é implementável só com APIs públicas.** Porém, a parte de estado necessária à experiência final já pode ser usada por uma integração externa compatível no Android 17.

Rotas reais para o produto final:

1. parceria OEM / app privilegiado / integração de ROM que exponha áudio da chamada SIM e possa entregar uma conexão compatível;
2. provedor de chamada externa/companion compatível com o novo estado de Audio Processing do Android 17;
3. rota VoIP/SIP controlada pelo app (permite áudio, mas a telefonia deixa de ser estritamente offline);
4. futura ampliação da API pública para PSTN comum.

## Estado atual do MVP público

Implementado:

- `ROLE_CALL_SCREENING`;
- resposta conservadora dentro da janela do Android;
- **nenhum número demo é usado como regra real**;
- falha de verificação da operadora é sinal de risco, não prova de spam, e só é consultada no API 30+;
- política pura `AudioProcessingEligibility` para separar API 37/chamada externa de PSTN comum;
- `Android17AudioProcessingController` com `CALL_SCREENING → SIMULATED_RINGING → ACTIVE` e validação de ordem/eligibilidade;
- histórico local cifrado com AES-GCM e chave Android Keystore;
- sem permissão `INTERNET`, sem cloud backup e sem device-transfer dos dados privados;
- exclusão explícita e destrutiva do histórico, ciphertext legado e chave AES;
- FunctionGemma via LiteRT-LM, modelo fora do APK;
- tool calling **manual**, permitindo validação antes de qualquer ação;
- política determinística que valida categoria, confiança finita e cardinalidade;
- modelo importado de forma atômica, com SHA-256 e estado de confiança;
- SHA-256 recalculado do arquivo real antes de qualquer carregamento de produção;
- nenhum hash marcado como confiável até existir uma release CallGuard avaliada;
- laboratório de transcrição;
- exemplos solicitados claramente marcados `DEMO`;
- abstração de áudio que exige entrada PCM, saída PCM, conexão ao usuário e término da chamada;
- GitHub Actions como único caminho de build/test/lint/debug/release.

Até existir uma ponte de áudio, chamadas desconhecidas sem regra determinística são **permitidas**, não bloqueadas por suposição.

## Estratégia híbrida aplicada

A arquitetura agora trata duas capacidades separadamente:

```text
CONTROLE TELECOM                         TRANSPORTE DE ÁUDIO

PSTN comum                              CallScreeningService
  └─ permitir/bloquear/silenciar          └─ sem PCM bidirecional público

Android 17 + EXTERNAL_CALL              Provedor externo/OEM/VoIP
  ├─ STATE_AUDIO_PROCESSING               ├─ PCM do chamador
  ├─ CALL_SCREENING use case              └─ PCM para o chamador
  └─ STATE_SIMULATED_RINGING
```

Quando uma integração futura fornecer `Connection` externa + PCM, o CallGuard não precisará reinventar o estado de telefonia: o controlador API 37 já implementa a transição pública correta. Para PSTN comum, o app continua conservador e não tenta APIs escondidas.

## Pipeline offline planejado

```text
Ponte de áudio suportada
        ↓
PCM 8/16 kHz
        ↓
VAD local ──── silêncio confirmado / turnos
        ↓
STT local PT-BR
        ↓
transcrição + evidências acústicas
        ↓
FunctionGemma 270M fine-tuned
        ↓
  tool call proposta
        ↓
validador determinístico
   │       │        │
allow   block     askAgain
   ↓       ↓        ↓
ponte de telefonia / novo turno
```

### VAD

Candidato inicial: **Silero VAD**, modelo pequeno e permissivo. Silêncio deve ser inferido por VAD/temporização de áudio; **transcrição vazia nunca é prova de silêncio**.

### STT

Candidato inicial para benchmark: **whisper.cpp multilingual Base Q5_1 (~57 MB)**. Só será promovido após teste em áudio telefônico PT-BR, sotaques, ruído e aparelhos reais. Tiny pode ser comparado por velocidade, mas não será escolhido apenas por tamanho.

### TTS

Primeiro caminho: `TextToSpeech` do Android usando somente uma `Voice` que declare `isNetworkConnectionRequired == false`, sintetizando para PCM/arquivo local. Se qualidade/disponibilidade forem insuficientes, escolher um modelo TTS embarcado após benchmark e revisão de licença.

## FunctionGemma e segurança de decisão

O modelo **não executa telefonia**. Ele só propõe uma ferramenta.

- `allowCall`: categorias `REAL_PERSON`, `DELIVERY`, `JOB`, `HEALTH`, `SERVICE`; confiança mínima do validador: 0,70.
- `blockCall`: `OPERATOR`, `MARKETING`, `SCAM`, `ROBOT_OR_SILENT`; confiança mínima do validador: 0,90.
- `askForClarification`: opção preferida em qualquer ambiguidade.

Categoria incompatível, confiança ausente/baixa/não finita, ferramenta desconhecida ou múltiplas tool calls viram `PENDING`.

A confiança emitida pelo LLM não é tratada como probabilidade calibrada. Os limiares atuais são guardrails de desenvolvimento e deverão ser calibrados no conjunto de teste bloqueado. A decisão de telefonia final deverá combinar a classificação com evidências do VAD/STT e a política determinística da sessão, e não tratar a autoconfiança do LLM como probabilidade real.

## Segurança contra conteúdo adversarial

A fala do chamador é entrada não confiável. O prompt instrui o modelo a ignorar comandos contidos na transcrição e o código restringe o efeito final por schema, allowlists de categoria, confiança e cardinalidade da tool call. O dataset contém casos de prompt injection e marketing/golpe disfarçados.

## Modelo e distribuição

O APK não contém o FunctionGemma. Como o app não possui permissão de Internet, o arquivo `.litertlm` é obtido externamente e importado pelo seletor de arquivos.

A instalação:

- exige extensão `.litertlm` quando o provedor informa nome;
- limita tamanho esperado;
- copia para arquivo temporário e sincroniza em disco;
- preserva o modelo anterior até a troca terminar;
- calcula SHA-256;
- registra se o hash pertence à allowlist compilada.

O metadado salvo **não autoriza produção sozinho**. Antes de inicializar um modelo em modo de produção, o app recalcula o SHA-256 do arquivo exato que será executado e exige que ele corresponda à allowlist. Arquivo corrompido, substituído ou sem hash confiável resulta em `PENDING`.

A allowlist está vazia até produzirmos um modelo oficial. Uma release de produção deverá publicar manifesto com versão, hash, tamanho, runtime testado e métricas do conjunto `test_locked`.

O fato de o modelo estar fora do APK não torna o runtime pequeno. O artifact debug universal do run #107 ficou em aproximadamente **107 MiB**, dominado pelo LiteRT-LM, DEX/dependências e bibliotecas nativas para duas ABIs. Esse valor é referência de desenvolvimento, não meta de release. A distribuição final deve usar App Bundle/ABI splits quando aplicável, avaliar R8/minificação com regras compatíveis com o runtime e medir tamanho real por arquitetura antes de qualquer promessa pública.

## Dataset e gates de produção

O seed é somente desenvolvimento. O fine-tuning sério deve ter `train`, `validation`, `test_locked`, `red_team` e, se houver consentimento, `real_opt_in`.

Antes de ativar bloqueio por IA:

- falso bloqueio de chamadas legítimas <= 0,5%;
- 100% das ações executadas passam pelo schema/política do app;
- 0 bloqueios por ausência de texto sem evidência acústica;
- >= 99,5% dos casos red-team sem decisão terminal incoerente;
- matriz de confusão por categoria e por tipo de erro de STT;
- regressão após mudança de modelo, prompt, quantização ou LiteRT-LM.

## Privacidade

- aplicativo sem `INTERNET`;
- inferência local;
- histórico cifrado por AES-GCM com chave no Android Keystore;
- backup Android e transferência automática de dados privados desativados;
- usuário pode apagar o ciphertext e a chave do histórico;
- dados reais para treino somente com opt-in e anonimização;
- transcrições não entram em logs/telemetria de rede.

## Fases

### Fase 1 — base Android / IA de laboratório

- [x] UI Compose e exemplos solicitados com selo DEMO;
- [x] screening role e serviço;
- [x] política de screening conservadora;
- [x] histórico cifrado e exclusão destrutiva;
- [x] importação atômica de `.litertlm`, SHA-256 e revalidação de integridade em produção;
- [x] FunctionGemma com tool calling manual;
- [x] guardrails determinísticos e testes unitários;
- [x] fallback GPU → CPU;
- [x] dataset seed adversarial e gates de avaliação;
- [x] fronteira explícita de áudio;
- [x] build debug e release/AAB no GitHub Actions.

### Fase 2 — modelos de voz, ainda fora de chamada real

- [ ] integrar/benchmarkar Silero VAD;
- [ ] integrar/benchmarkar Whisper Base Q5_1 e alternativas;
- [ ] validar TTS offline Android e fallback local;
- [ ] coletar corpus telefônico consentido/anonimizado ou corpus público adequado;
- [ ] criar milhares de exemplos de tool calling;
- [ ] fine-tune FunctionGemma;
- [ ] converter/quantizar `.litertlm`;
- [ ] produzir primeiro manifesto/hash confiável;
- [ ] medir latência, RAM, temperatura e bateria em várias classes de aparelho.

### Fase 3 — integração de telefonia

- [x] implementar controle Android 17 `AUDIO_PROCESSING/CALL_SCREENING/SIMULATED_RINGING` para `PROPERTY_IS_EXTERNAL_CALL`;
- [ ] escolher/obter uma rota real de PCM para chamadas SIM (OEM/privilegiada) ou provedor externo compatível;
- [ ] provar PCM bidirecional estável;
- [ ] provar que o chamador pode conversar enquanto o usuário não é interrompido;
- [ ] conectar a aprovação da IA a `setSimulatedRinging()`/handoff do provedor;
- [ ] integrar VAD/STT/TTS/FunctionGemma à sessão real;
- [ ] testar chamadas de emergência, espera, queda de app, troca de rede e Bluetooth.

### Fase 4 — release

- [ ] conjunto `test_locked` congelado e relatório público/interno de métricas;
- [ ] modelo confiável na allowlist;
- [ ] testes instrumentados e matriz de dispositivos;
- [ ] política de retenção configurável;
- [ ] regras do usuário: sempre permitir/bloquear;
- [ ] gerar App Bundle/ABI splits e medir download/instalação por arquitetura;
- [ ] validar R8/minificação com LiteRT-LM e comparar tamanho/latência antes e depois;
- [ ] definir meta de tamanho somente após medição do pacote otimizado;
- [ ] configurar keystore de produção para releases assinadas;
- [ ] revisão de LGPD, consentimento de gravação/transcrição e requisitos da Play Store/distribuição escolhida;
- [ ] política de rollback de app e modelo.
