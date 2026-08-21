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
7. Promessas de produto precisam ser sustentadas pelo artefato medido; modelo fora do APK não implica automaticamente pacote pequeno.

## Áreas

| Área | Estado do Crítico | Principais achados/correções |
|---|---|---|
| 1. Build / CI / dependências | **APROVADO** | SDK Android 17 usa `platforms;android-37.0`; Actions Node 24; Gradle 9.5; JDK 21 para LiteRT-LM/testes; código do app continua Java 17. |
| 2. FunctionGemma / runtime | **APROVADO** | Automatic tool calling removido; tool calls manuais; categoria/confiança/cardinalidade validadas; NaN/infinito rejeitados; LiteRT-LM 0.16.1; falha de modelo vira `PENDING`. |
| 3. Telefonia Android | **APROVADO COM LIMITAÇÃO DE PLATAFORMA** | Números DEMO removidos das regras reais; screening conservador; resposta antes de I/O; API 29 protegida do `callerNumberVerificationStatus` de API 30; áudio bidirecional da operadora continua indisponível para APK público. |
| 4. Privacidade / armazenamento | **APROVADO** | Sem `INTERNET`; backup e device-transfer desativados; AES-GCM/Keystore; exclusão remove ciphertext, legado e chave; I/O/Keystore fora do caminho crítico do screening. |
| 5. Dataset / avaliação | **APROVADO COMO BASE DE DESENVOLVIMENTO** | Seed com 56 casos adversariais; vazio não significa silêncio; SCAM explícito; validador rejeita combinações perigosas; gates de produção e `test_locked` definidos. |
| 6. UI / UX / acessibilidade | **APROVADO EM ESTRUTURA** | Registros fictícios marcados DEMO; modelo não verificado sinalizado; ícone presente; removida a promessa não sustentada de “APK permanece leve”. |
| 7. Testes / falhas | **APROVADO** | 15 testes passaram, 0 falhas e 0 ignorados; regressões cobrem categorias incoerentes, baixa confiança, tool desconhecida, sanitização e confiança não finita. |
| 8. Modelo / distribuição / integridade | **APROVADO PARA O MVP** | Instalação atômica, limite de tamanho, SHA-256 e allowlist; produção recalcula hash. O APK debug universal ~107 MiB está documentado; App Bundle/ABI splits/R8 + medição permanecem gates de release, não promessas do MVP. |
| 9. Áudio / STT / TTS / VAD | **APROVADO NO DESENHO, BLOQUEADO PELA PLATAFORMA** | Ponte exige PCM entrada/saída, `connectToUser()` e `terminate()`; candidatos VAD/STT/TTS documentados; nenhuma implementação pública finge possuir áudio da chamada da operadora. |
| 10. Documentação / escopo / release | **APROVADO** | README/PLAN/PRIVACY/THIRD_PARTY distinguem MVP, laboratório e produto final; limitações, licenças, LGPD, tamanho do pacote e requisitos de release estão explícitos. |

## Evidência de CI acumulada

- **Run #59:** reprovado no SDK; revelou que `platforms;android-37` não era o nome instalável atual.
- **Run #69:** SDK corrigido, avançou até Kotlin; revelou import ausente de `setContent`.
- **Run #73:** compilação Kotlin completa; revelou que LiteRT-LM requer Java 21 nos testes.
- **Run #75:** JDK 21 resolveu o runtime; **testes unitários passaram**; lint então revelou incompatibilidade API 29/API 30 e avisos de manifesto/dependência/estilo.
- **Run #107:** dataset, 15 testes, lint, montagem do APK e artifacts passaram; lint teve 0 issues. A auditoria posterior do artifact encontrou a promessa incorreta de APK “leve”, removida em seguida.
- **Run #115:** executou novamente o commit com a última correção de UI/documentação e terminou **100% verde**: SDK, dataset, 15 testes, Android lint, `assembleDebug`, upload do APK e upload dos relatórios passaram.

## Limitações que permanecem conscientemente

A aprovação do Crítico é para o **MVP que o repositório realmente implementa**, não para a experiência final de atendimento autônomo de chamadas celulares. Permanecem como etapas externas/futuras já documentadas:

- falta de API pública Android para áudio PCM bidirecional da chamada celular da operadora;
- ausência de fine-tune CallGuard aprovado e, portanto, allowlist de modelo ainda vazia;
- VAD/STT/TTS ainda precisam de benchmark e integração quando existir uma ponte de áudio;
- target Android 17 deve ser promovido após testes das mudanças de comportamento;
- distribuição final precisa de App Bundle/ABI splits/R8, medição de tamanho e testes em dispositivos reais;
- bloqueio por IA em produção exige `test_locked`, red-team e metas de falso bloqueio já definidas.

Esses itens não são apresentados como funcionalidades concluídas e, por isso, não constituem defeitos ocultados do MVP atual.

## Critério global

O critério global foi atendido pelo run #115: o commit com a última mudança funcional/copy passou validação de dataset, testes unitários, Android lint, montagem do APK e upload dos artifacts. Todas as áreas corrigíveis encontradas pelo Crítico foram tratadas; o que resta são limitações reais de plataforma ou gates futuros explicitamente fora do MVP.

**Estado global: SATISFEITA.**
