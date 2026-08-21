# Fine-tuning do FunctionGemma para CallGuard

O FunctionGemma 270M é a base para especialização. Ele **não** deve ser tratado como classificador de telefonia pronto e nenhum modelo pode ganhar autoridade de bloqueio apenas por ter sido fine-tuned.

## Dataset de desenvolvimento

`dataset_seed.jsonl` usa uma representação simples e revisável por humanos:

```json
{"transcript":"sou entregador e estou na portaria","expected_tool":"allowCall","arguments":{"callerName":"","category":"DELIVERY","summary":"Entrega na portaria.","confidence":0.98}}
```

`validate_dataset.py` valida:

- ferramenta e formato dos argumentos;
- categorias permitidas para cada ação;
- duplicatas/conflitos;
- distribuição mínima entre `allowCall`, `blockCall` e `askForClarification`;
- proibição de usar transcrição vazia como prova de silêncio.

O seed serve para desenvolvimento e regressão estrutural. **Não é benchmark de produção.**

## Formato final de treino

Na etapa de treinamento, os registros simples devem ser convertidos ao formato de conversa/tool calling do FunctionGemma, incluindo as declarações das três ferramentas. O fine-tuning deve preservar a separação entre:

- dado do chamador (não confiável);
- instrução de sistema;
- tool call estruturada;
- validação determinística executada pelo aplicativo.

## Separação obrigatória dos dados

1. `train`: usado no fine-tuning.
2. `validation`: escolha de hiperparâmetros/early stopping.
3. `test_locked`: nunca usado para gerar templates, treinar ou ajustar limiares.
4. `red_team`: prompt injection, linguagem adversarial, marketing disfarçado, golpes e ruído de STT.
5. `real_opt_in`: somente exemplos reais com consentimento e anonimização, separados por origem para evitar vazamento entre splits.

Frases derivadas do mesmo template semântico devem permanecer no mesmo split.

## Gates antes de habilitar bloqueio por IA

A release não pode ativar bloqueio automático do modelo até cumprir **todos** os gates em `test_locked`:

- **falso bloqueio de chamadas legítimas <= 0,5%**;
- **100% das tool calls aceitas pelo app respeitam o schema/política determinística**;
- **0 bloqueios por ausência de transcrição sem evidência de VAD**;
- **>= 99,5% de resistência** a casos de prompt injection do conjunto red-team (resultado não pode ser uma decisão terminal incoerente);
- relatório de matriz de confusão por categoria e por tipo de erro de STT;
- resultados separados por fala formal/informal, regionalismos e áudio degradado;
- regressão executada novamente após qualquer mudança de modelo, prompt, quantização ou runtime LiteRT-LM.

`askForClarification` é um resultado correto: a meta não é maximizar decisões, mas minimizar decisões erradas.

## Estratégia de dados

- Começar com 1–2 mil exemplos por intenção importante para a primeira avaliação séria.
- Incluir PT-BR formal, informal, regionalismos e frases incompletas.
- Injetar erros realistas produzidos pelo STT escolhido, não apenas erros artificiais.
- Dar peso maior a casos em que um falso bloqueio seria grave.
- Ter grande diversidade de `askForClarification` para entradas ambíguas.
- Tratar silêncio, VAD e detecção acústica de robô como **evidência de áudio separada**, não como texto inventado.
- Usar chamadas reais somente com consentimento, minimização e anonimização.

## Quantização e distribuição

O artefato distribuído ao app deve ser convertido e quantizado para `.litertlm`. O modelo fica fora do APK e é instalado em `files/models/functiongemma-callguard.litertlm`.

Cada release do modelo deverá ter manifesto com versão, tamanho, SHA-256, runtime LiteRT-LM testado e métricas do `test_locked`. O aplicativo já calcula o SHA-256 do arquivo instalado; o passo seguinte, quando houver um artefato oficial do CallGuard, é validar esse hash contra o manifesto assinado da release.
