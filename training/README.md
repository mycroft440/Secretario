# Fine-tuning do FunctionGemma para CallGuard

O FunctionGemma 270M deve ser tratado como base para especialização, não como um classificador de telefonia pronto.

## Dataset

`dataset_seed.jsonl` usa uma representação simples, fácil de revisar por humanos:

```json
{"transcript":"sou entregador e estou na portaria","expected_tool":"allowCall","arguments":{"callerName":"","category":"DELIVERY","summary":"Entrega na portaria.","confidence":0.98}}
```

`validate_dataset.py` valida campos, ferramentas e categorias. Rode:

```bash
python3 training/validate_dataset.py
```

## Formato final de treino

Na etapa de treinamento, os registros simples devem ser convertidos ao formato de conversa/tool calling exigido pelo FunctionGemma, incluindo as declarações das três ferramentas do aplicativo. O notebook oficial de Mobile Actions do Google é a referência de implementação para TRL + conversão final para `.litertlm`.

## Estratégia de dados

1. 1–2 mil exemplos por intenção importante para a primeira avaliação séria.
2. Variações PT-BR: formal, informal, regionalismos, frases incompletas.
3. Adicionar ruído de STT deliberadamente.
4. Separar treino/validação/teste por template semântico para evitar vazamento.
5. Dar peso especial a falsos bloqueios.
6. Incluir muitos `askForClarification` em frases realmente ambíguas.
7. Só usar chamadas reais com consentimento e anonimização.

## Quantização

O artefato distribuído ao app deve ser convertido e quantizado para `.litertlm`. O modelo fica fora do APK e é instalado em `files/models/functiongemma-callguard.litertlm`.
