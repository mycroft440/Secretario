# Dependências, modelos e licenças

Este arquivo é um inventário de desenvolvimento, não substitui revisão jurídica antes da distribuição.

## Já usado no código

### LiteRT-LM

- Projeto: Google AI Edge LiteRT-LM.
- Uso: runtime Android para o FunctionGemma `.litertlm`.
- Licença do código: Apache License 2.0.
- A release do app deve preservar avisos/licenças exigidos pelas dependências transitivas.

### FunctionGemma 270M

- Modelo do Google DeepMind, baseado no Gemma 3 270M e especializado em function calling.
- O próprio model card recomenda fine-tuning para a tarefa específica; não é tratado neste projeto como classificador pronto.
- **Pesos e modelos derivados estão sujeitos aos Gemma Terms of Use e à política de usos proibidos**, não simplesmente à licença Apache do código de exemplo/runtime.
- Qualquer distribuição de um fine-tune CallGuard deve revisar e cumprir os termos vigentes da Gemma, incluindo obrigações aplicáveis à distribuição de derivados.
- O modelo não é incluído no APK atual.

## Planejado, ainda não integrado

### whisper.cpp / Whisper

- Candidato para STT local.
- whisper.cpp: MIT.
- O código e pesos oficiais do Whisper também são publicados sob MIT.
- O modelo exato/quantização só será incorporado após benchmark PT-BR telefônico e revisão dos arquivos que serão redistribuídos.

### Silero VAD

- Candidato para detecção local de atividade de voz.
- Licença: MIT.
- O projeto suporta 8 kHz e 16 kHz, úteis para telefonia.

### Android TextToSpeech

- Primeiro candidato de TTS para protótipo, usando apenas voz que indique não exigir rede.
- Uma engine TTS pode ser fornecida pelo sistema ou por outro aplicativo. Para uma garantia forte de privacidade/consistência na release final, uma voz/modelo embarcado e auditado pode ser preferível.

## Regra de release

Antes de adicionar um binário, modelo ou peso ao APK/release:

1. registrar versão/origem;
2. registrar licença/termos;
3. registrar hash do artefato;
4. verificar obrigações de redistribuição/atribuição;
5. revisar dependências transitivas;
6. executar o conjunto de avaliação correspondente;
7. não marcar o modelo como confiável até passar os gates do projeto.
