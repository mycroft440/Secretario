# Revisão Crítico → Executor

Este documento registra a revisão do CallGuard AI por duas funções separadas:

- **Crítico:** procura falhas, riscos, promessas não sustentadas, testes ausentes e melhorias. Revisa uma área por vez e define critério de aprovação.
- **Executor:** implementa as correções e apresenta evidência. Não pode declarar uma área aprovada sem satisfazer o critério do Crítico.

A separação é de função e checklist dentro deste trabalho; não existe um processo autônomo rodando em background. Build e testes Android são executados exclusivamente no GitHub Actions.

## Regras de aprovação

1. Uma área reprovada bloqueia a aprovação global.
2. Problemas críticos/altos precisam ser corrigidos; limitações de plataforma impossíveis de remover devem estar explicitamente documentadas e não podem ser apresentadas como implementadas.
3. Toda mudança de código precisa passar por testes/lint/build no GitHub Actions.
4. A IA nunca recebe autoridade direta para bloquear/permitir: tool calls passam por política determinística.
5. Falso bloqueio é tratado como erro mais grave que abstenção (`PENDING`).

## Áreas

| Área | Estado do Crítico | Principais achados/correções |
|---|---|---|
| 1. Build / CI / dependências | PENDENTE CI | SDK 37 não era encontrado; workflow modernizado para setup-android v4, ações Node 24 e Compose BOM 2026.08.00. |
| 2. FunctionGemma / runtime | PENDENTE CI | Removido automatic tool calling; tool calls agora são manuais e validadas por categoria/confiança. |
| 3. Telefonia Android | APROVADO COM LIMITAÇÃO | Removidos números demo das regras reais; resposta conservadora em <5 s. Ponte de áudio de operadora continua indisponível via API pública. |
| 4. Privacidade / armazenamento | PENDENTE CI | INTERNET removido, backup desativado, histórico AES-GCM/Keystore, exclusão explícita. |
| 5. Dataset / avaliação | APROVADO COMO BASE | Seed adversarial ampliado; validação estrutural reforçada; gates de produção definidos. |
| 6. UI / UX / acessibilidade | EM REVISÃO | — |
| 7. Testes / observabilidade / falhas | A REVISAR | — |
| 8. Modelo / distribuição / integridade | A REVISAR | — |
| 9. Arquitetura de áudio / STT / TTS / VAD | A REVISAR | — |
| 10. Documentação / escopo / release | A REVISAR | — |

## Critério global

A revisão só termina como **SATISFEITA** quando todas as áreas corrigíveis estiverem aprovadas, o CI da branch estiver verde e as limitações restantes forem fronteiras reais da plataforma documentadas com um caminho técnico de continuação.
