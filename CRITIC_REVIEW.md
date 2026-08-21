# Revisão Crítico → Executor

Este documento registra a revisão do CallGuard AI por duas funções separadas:

- **Crítico:** procura falhas, riscos, promessas não sustentadas, testes ausentes e melhorias. Revisa uma área por vez e define critério de aprovação.
- **Executor:** implementa as correções e apresenta evidência. Não pode declarar uma área aprovada sem satisfazer o critério do Crítico.

A separação é de função e checklist dentro deste trabalho; não existe um processo autônomo rodando em background. Build e testes Android são executados exclusivamente no GitHub Actions.

## Regras de aprovação

1. Uma área reprovada bloqueia a aprovação global.
2. Problemas críticos/altos precisam ser corrigidos; limitações de plataforma impossíveis de remover devem estar explicitamente documentadas e não podem ser apresentadas como implementadas.
3. Toda mudança de código precisa passar por testes/lint/build no GitHub Actions.
4. A IA nunca recebe autoridade direta para controlar telefonia: tool calls passam por política determinística e o caminho final ainda exige evidências da sessão.
5. Falso bloqueio é tratado como erro mais grave que abstenção (`PENDING`).
6. Metadado salvo não substitui verificação de integridade do artefato real executado.

## Áreas

| Área | Estado do Crítico | Principais achados/correções |
|---|---|---|
| 1. Build / CI / dependências | **PENDENTE CI FINAL** | SDK Android 17 corrigido para `platforms;android-37.0`; Actions atualizadas para runtimes Node 24; Gradle 9.5; JDK 21 para executar LiteRT-LM/testes; código do app continua Java 17. |
| 2. FunctionGemma / runtime | **PENDENTE CI FINAL** | Automatic tool calling removido; tool calls manuais; categoria/confiança/cardinalidade validadas; NaN/infinito rejeitados; LiteRT-LM atualizado para 0.16.1; falha de modelo vira `PENDING`. |
| 3. Telefonia Android | **APROVADO COM LIMITAÇÃO DE PLATAFORMA** | Números DEMO removidos das regras reais; screening conservador; resposta antes de I/O; API 29 protegida do `callerNumberVerificationStatus` de API 30; áudio bidirecional da operadora continua indisponível para APK público. |
| 4. Privacidade / armazenamento | **PENDENTE CI FINAL** | Sem `INTERNET`; backup e device-transfer desativados; AES-GCM/Keystore; exclusão remove ciphertext, legado e chave; I/O/Keystore fora do caminho crítico do screening. |
| 5. Dataset / avaliação | **APROVADO COMO BASE DE DESENVOLVIMENTO** | Seed com 56 casos adversariais; vazio não significa silêncio; SCAM explícito; validador rejeita combinações perigosas; gates de produção e `test_locked` definidos. |
| 6. UI / UX / acessibilidade | **APROVADO EM ESTRUTURA** | Registros fictícios marcados DEMO; “PERMITIDA” não finge identidade verificada; modelo não verificado é sinalizado; controles principais suportam melhor fonte grande; ícone do app adicionado. |
| 7. Testes / falhas | **PENDENTE REGRESSÃO CI** | Run #75 comprovou testes unitários verdes sob JDK 21; regressões cobrem categorias incoerentes, baixa confiança, tool desconhecida, sanitização e confiança não finita; alterações posteriores exigem novo run. |
| 8. Modelo / distribuição / integridade | **PENDENTE CI FINAL** | Instalação atômica, limite de tamanho e SHA-256; allowlist começa vazia; produção recalcula o SHA-256 do arquivo real antes de carregar e rejeita corrupção/substituição. |
| 9. Áudio / STT / TTS / VAD | **APROVADO NO DESENHO, BLOQUEADO PELA PLATAFORMA** | Ponte exige PCM entrada/saída, `connectToUser()` e `terminate()`; candidatos VAD/STT/TTS documentados; nenhuma implementação pública finge possuir áudio da chamada da operadora. |
| 10. Documentação / escopo / release | **APROVADO, PENDENTE RESULTADO FINAL DO CI** | README/PLAN/PRIVACY/THIRD_PARTY distinguem MVP, laboratório e produto final; limitações, licenças, LGPD e requisitos de release estão explícitos. |

## Evidência de CI acumulada

- **Run #59:** reprovado no SDK; revelou que `platforms;android-37` não era o nome instalável atual.
- **Run #69:** SDK corrigido, avançou até Kotlin; revelou import ausente de `setContent`.
- **Run #73:** compilação Kotlin completa; revelou que LiteRT-LM requer Java 21 nos testes.
- **Run #75:** JDK 21 resolveu o runtime; **testes unitários passaram**; lint então revelou incompatibilidade API 29/API 30 e avisos de manifesto/dependência/estilo, todos tratados na revisão seguinte.

## Critério global

A revisão só termina como **SATISFEITA** quando o commit final da branch passar validação de dataset, testes unitários, Android lint, montagem do APK e upload do artefato no GitHub Actions. As limitações restantes devem ser somente fronteiras reais da plataforma ou etapas futuras explicitamente fora do MVP, nunca defeitos ocultados.

**Estado global atual: PENDENTE DO CI FINAL.**
