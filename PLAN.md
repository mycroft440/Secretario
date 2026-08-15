# CallGuard AI — plano técnico executável

## Objetivo de produto

Para números desconhecidos, o produto final deve manter o usuário sem interrupção enquanto um agente local pergunta quem está ligando e por quê. A IA classifica a intenção e somente uma chamada legítima deve chegar ao usuário.

Exemplo de experiência desejada:

1. número desconhecido chama;
2. o aparelho do usuário não toca;
3. uma voz local pergunta: “Olá. Diga seu nome e o motivo da ligação.”;
4. STT local produz a transcrição;
5. FunctionGemma escolhe uma ação estruturada;
6. spam/robô/silêncio é encerrado;
7. ligação legítima é apresentada ao usuário com nome/motivo.

## Restrição de plataforma que muda a arquitetura

`CallScreeningService` é ótimo para bloquear/silenciar/permitir, porém precisa responder em até 5 segundos e sua API pública não fornece um canal bidirecional de áudio da chamada da operadora para um APK comum. Além disso, não há um segundo `respondToCall` para silenciar uma chamada e depois fazê-la voltar a tocar.

Por isso o projeto separa dois produtos:

### MVP público Android

- papel de `ROLE_CALL_SCREENING`;
- bloqueio determinístico de spam já conhecido;
- histórico persistente;
- FunctionGemma 270M local;
- laboratório para validar transcrições e decisões;
- APK pequeno, modelo `.litertlm` instalado separadamente;
- interface preparada para receber uma futura ponte de áudio.

### Produto final de triagem silenciosa

Precisa de uma destas rotas de telefonia:

1. integração OEM/privilegiada com acesso ao áudio de chamada;
2. caminho VoIP/SIP em que o áudio já pertence ao app;
3. integração nativa com fabricante/ROM;
4. outra API futura do Android que exponha o áudio de triagem.

Não será usado um “hack” de microfone/alto-falante como base do produto por ser frágil, incompatível entre aparelhos e inadequado para privacidade.

## Pipeline local de IA

```text
Áudio disponível pela ponte
        ↓
VAD local
        ↓
STT local PT-BR
        ↓
transcrição curta
        ↓
FunctionGemma 270M fine-tuned
        ↓
┌────────────────┬───────────────────┬──────────────────────┐
│ allowCall      │ blockCall         │ askForClarification  │
└────────────────┴───────────────────┴──────────────────────┘
        ↓
Política determinística do app
        ↓
Histórico + ação de telefonia
```

O FunctionGemma nunca recebe autorização direta para executar APIs de telefonia. Ele apenas escolhe uma função de domínio. O aplicativo valida e executa a decisão.

## Funções expostas ao modelo

### `allowCall`

Parâmetros: nome declarado, categoria, resumo, confiança.

Usos: pessoa real, entrega, emprego, saúde, serviço solicitado.

### `blockCall`

Parâmetros: categoria, motivo, confiança.

Usos: telemarketing, oferta de operadora, robô, gravação automática, silêncio.

### `askForClarification`

Parâmetro: uma pergunta curta.

Usado quando “quero falar sobre um assunto” não informa o suficiente. O estado entre turnos será mantido pelo aplicativo, não delegado implicitamente ao modelo.

## Fine-tuning

O modelo de 270M deve ser especializado em português brasileiro e telefonia. Dataset inicial inclui:

- diferentes formas de dizer a mesma intenção;
- erros típicos do STT;
- gírias e linguagem informal;
- chamadas legítimas parecidas com marketing;
- marketing tentando parecer urgente;
- entregadores e portaria;
- retorno de emprego;
- conhecidos com número novo;
- robôs e chamadas sem fala;
- casos ambíguos que exigem uma segunda pergunta.

Meta antes de produção: conjunto de teste separado por falante/frase e análise específica de falso bloqueio. Falso bloqueio de chamadas legítimas deve receber custo muito maior que um spam que eventualmente passe.

## Política de confiança

A confiança produzida pelo LLM não será tratada como probabilidade calibrada. A política de produção deve combinar:

- tipo de função escolhida;
- regras determinísticas;
- repetição da classificação;
- sinais do STT/VAD;
- contexto local permitido pelo usuário;
- testes de calibração no dataset real.

Quando houver dúvida, `askForClarification` é preferível a bloquear.

## Privacidade

Após o modelo e modelos de voz serem instalados:

- inferência local;
- nenhuma transcrição enviada à nuvem;
- histórico armazenado no aparelho;
- retenção configurável numa etapa futura;
- dados reais para fine-tuning somente com consentimento explícito e anonimização.

## Fases

### Fase 1 — executada neste MVP

- [x] app Compose;
- [x] tela com “Ligações do dia” e os 3 exemplos solicitados;
- [x] tela “Ligações reais” com Roberto;
- [x] `ROLE_CALL_SCREENING`;
- [x] política rápida separada do LLM;
- [x] histórico persistente;
- [x] importação de `.litertlm` separado do APK;
- [x] SHA-256 do modelo importado;
- [x] FunctionGemma com ferramentas estruturadas;
- [x] fallback CPU quando GPU não inicializa;
- [x] laboratório de transcrições dentro do app;
- [x] abstração para a futura ponte de áudio;
- [x] CI para testes, lint e APK;
- [x] política de projeto: nenhuma compilação local, build somente no GitHub Actions;
- [x] carregamento/instalação do modelo fora da thread principal;
- [x] falha de verificação da operadora tratada como risco, não como bloqueio automático.

### Fase 2 — próxima

- [ ] escolher STT local após benchmark real em PT-BR telefônico;
- [ ] VAD local;
- [ ] TTS local;
- [ ] criar dataset de milhares de exemplos;
- [ ] fine-tune FunctionGemma;
- [ ] converter/quantizar para `.litertlm`;
- [ ] medir latência, RAM, temperatura e bateria em celulares de entrada/intermediários.

### Fase 3 — desbloqueadora do produto final

- [ ] prototipar uma rota de áudio real (OEM/privilegiada ou VoIP/SIP);
- [ ] provar que a chamada pode permanecer silenciosa durante a conversa;
- [ ] provar que uma chamada aprovada consegue ser entregue/acionar o usuário;
- [ ] só então integrar STT/TTS ao caminho de chamada real.

### Fase 4 — produção

- [ ] telemetria local de qualidade opt-in;
- [ ] atualização assinada do modelo;
- [ ] proteção contra modelo corrompido;
- [ ] testes de regressão de falso bloqueio;
- [ ] configuração do usuário: sempre permitir/bloquear categorias;
- [ ] política para emergência e números recorrentes.
