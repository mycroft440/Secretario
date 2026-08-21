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

## Limite duro do Android público

Um APK comum com `CallScreeningService` consegue permitir, silenciar ou rejeitar uma chamada e deve responder em até 5 segundos. Ele **não recebe o áudio bidirecional PCM da chamada celular da operadora** e não possui uma segunda resposta para silenciar uma chamada e, depois, religar o toque da mesma chamada.

Além disso, sem `READ_CONTACTS`, o Android entrega ao screening justamente chamadas cujo número não está nos contatos — o comportamento desejado aqui. Chamadas com apresentação restrita/oculta/indisponível não são entregues ao `CallScreeningService`.

Consequência: **APK público + chamada celular convencional + conversa bidirecional da IA + 100% offline não é implementável hoje só com APIs públicas.** O projeto não simula essa capacidade.

Rotas reais para o produto final:

1. parceria OEM / app privilegiado / integração de ROM que exponha áudio da chamada;
2. rota VoIP/SIP controlada pelo app (permite áudio, mas a telefonia deixa de ser estritamente offline);
3. futura API pública equivalente do Android.

## Estado atual do MVP público

Implementado:

- `ROLE_CALL_SCREENING`;
- resposta conservadora dentro da janela do Android;
- **nenhum número demo é usado como regra real**;
- falha de verificação da operadora é sinal de risco, não prova de spam;
- histórico local cifrado com AES-GCM e chave Android Keystore;
- sem permissão `INTERNET` e sem backup do app;
- exclusão explícita do histórico;
- FunctionGemma via LiteRT-LM, modelo fora do APK;
- tool calling **manual**, evitando continuação automática do runtime e permitindo validação antes de qualquer ação;
- política determinística que valida categoria e confiança;
- modelo importado de forma atômica, com SHA-256 e estado de confiança;
- nenhum hash marcado como confiável até existir uma release CallGuard avaliada;
- laboratório de transcrição;
- exemplos solicitados claramente marcados `DEMO`;
- abstração de áudio que exige entrada PCM, saída PCM, conexão ao usuário e término da chamada;
- GitHub Actions como único caminho de build/test/lint/APK.

Até existir uma ponte de áudio, chamadas desconhecidas sem regra determinística são **permitidas**, não bloqueadas por suposição.

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

Categoria incompatível, confiança ausente/baixa, ferramenta desconhecida ou múltiplas tool calls viram `PENDING`.

A confiança emitida pelo LLM não é tratada como probabilidade calibrada. Os limiares atuais são guardrails de desenvolvimento e deverão ser calibrados no conjunto de teste bloqueado.

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

A allowlist está vazia até produzirmos um modelo oficial. Uma release de produção deverá publicar manifesto com versão, hash, tamanho, runtime testado e métricas do conjunto `test_locked`.

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
- backup Android desativado;
- usuário pode apagar o histórico;
- dados reais para treino somente com opt-in e anonimização;
- transcrições não entram em logs/telemetria de rede.

## Fases

### Fase 1 — base Android / IA de laboratório

- [x] UI Compose e exemplos solicitados com selo DEMO;
- [x] screening role e serviço;
- [x] política de screening conservadora;
- [x] histórico cifrado e exclusão;
- [x] importação atômica de `.litertlm` e SHA-256;
- [x] FunctionGemma com tool calling manual;
- [x] guardrails determinísticos e testes unitários;
- [x] fallback GPU → CPU;
- [x] dataset seed adversarial e gates de avaliação;
- [x] fronteira explícita de áudio;
- [ ] GitHub Actions verde para a branch revisada.

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

### Fase 3 — desbloqueadora do produto final

- [ ] escolher uma rota real de áudio (OEM/privilegiada ou VoIP/SIP);
- [ ] provar PCM bidirecional estável;
- [ ] provar que o chamador pode conversar enquanto o usuário não é interrompido;
- [ ] provar `connectToUser()` depois da aprovação;
- [ ] integrar VAD/STT/TTS/FunctionGemma à sessão real;
- [ ] testar chamadas de emergência, espera, queda de app, troca de rede e Bluetooth.

### Fase 4 — release

- [ ] conjunto `test_locked` congelado e relatório público/interno de métricas;
- [ ] modelo confiável na allowlist;
- [ ] testes instrumentados e matriz de dispositivos;
- [ ] política de retenção configurável;
- [ ] regras do usuário: sempre permitir/bloquear;
- [ ] revisão de LGPD, consentimento de gravação/transcrição e requisitos da Play Store/distribuição escolhida;
- [ ] política de rollback de app e modelo.
