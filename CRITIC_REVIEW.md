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
8. Código `@SystemApi`/OEM nunca pode vazar para a edição pública nem ser apresentado como permissão obtida por um APK comum.

## Áreas

| Área | Estado do Crítico | Principais achados/correções |
|---|---|---|
| 1. Build / CI / dependências | **REABERTO — CI DOS FLAVORS** | CI já gerava debug + release/AAB; agora precisa provar `public` e `privileged` separadamente. |
| 2. FunctionGemma / runtime | **APROVADO** | Tool calls manuais; categoria/confiança/cardinalidade validadas; NaN/infinito rejeitados; LiteRT-LM 0.16.1; falha vira `PENDING`. |
| 3. Telefonia Android | **REABERTO — CI + HARDWARE OEM** | Public continua conservador. API 37 externa ganhou `AUDIO_PROCESSING → SIMULATED_RINGING`. Edição OEM ganhou default-dialer/InCallService e adapter para background audio processing. |
| 4. Privacidade / armazenamento | **APROVADO** | Sem `INTERNET`; backup/device-transfer desativados; AES-GCM/Keystore; exclusão destrutiva; I/O fora do screening crítico. |
| 5. Dataset / avaliação | **APROVADO COMO BASE DE DESENVOLVIMENTO** | Seed com 56 casos adversariais; vazio não significa silêncio; SCAM explícito; gates de produção definidos. |
| 6. UI / UX / acessibilidade | **APROVADO EM ESTRUTURA** | DEMO explícito; modelo não verificado sinalizado; pacote não é chamado de “leve”. Edição OEM possui UI mínima de dialer/in-call. |
| 7. Testes / falhas | **REABERTO — REGRESSÃO DOS FLAVORS** | Testes anteriores verdes; novos testes cobrem elegibilidade API 37. Ambos flavors precisam passar no run final. |
| 8. Modelo / distribuição / integridade | **REABERTO — DISTRIBUIÇÃO DUPLA** | Release pública e AAB existem; OEM deve permanecer artifact separado/unsigned para assinatura/política de sistema. |
| 9. Áudio / STT / TTS / VAD | **REABERTO — BRIDGE PCM OEM** | `PrivilegedPstnAudioBridge` agora implementa downlink/uplink via SystemApi refletida, `MODE_CALL_SCREENING`, capability checks e fail-safe. Ainda exige validação em aparelho/ROM autorizada; VAD/STT/TTS continuam próximos passos. |
| 10. Documentação / escopo / release | **APROVADO, PENDENTE SINCRONIA FINAL** | Public vs privileged/OEM e limites de SystemApi estão documentados em README/PLAN/oem. |

## Evidência de CI acumulada

- **Run #59:** revelou provisionamento incorreto do SDK 37.
- **Run #69:** revelou import Kotlin ausente.
- **Run #73:** revelou requisito de Java 21 do LiteRT-LM.
- **Run #75:** testes verdes; lint revelou incompatibilidade API 29/API 30 e avisos tratados.
- **Run #107:** dataset, 15 testes, lint, APK e artifacts verdes; auditoria do tamanho removeu promessa de APK “leve”.
- **Run #115:** última correção funcional/copy passou 100%.
- **Run #121:** commit documental da primeira revisão passou 100%.
- **Run #125:** adicionou release real e passou: debug, `assembleRelease`, `bundleRelease`, preparação e upload de `CallGuardAI-release` verdes.

## Nova rota privilegiada

A pesquisa no AOSP mostrou uma capacidade melhor que o simples `Connection.setAudioProcessing()` externo:

- o discador padrão/system path possui `Call.enterBackgroundAudioProcessing()` e `exitBackgroundAudioProcessing(...)` como `@SystemApi`;
- `AudioManager` possui `getCallDownlinkExtractionAudioRecord()` e `getCallUplinkInjectionAudioTrack()` como `@SystemApi`, protegidos por `CALL_AUDIO_INTERCEPTION`;
- `isPstnCallAudioInterceptable()` impede assumir que todo Audio HAL fornece essa rota;
- a edição privilegiada usa reflexão apenas para manter compilação contra o SDK público e falha fechada se ROM/permissão/API/hardware não forem compatíveis;
- a edição pública não contém essas classes nem solicita `CALL_AUDIO_INTERCEPTION`.

A aprovação de código não substitui validação em dispositivo OEM/system autorizado. Sem permissão real e suporte do Audio HAL, a capability fica indisponível e nenhuma chamada deve ser interceptada.

## Critério global atual

A primeira revisão ficou satisfeita até o run #121 e a release foi comprovada no #125. A adição posterior da rota privilegiada abriu uma nova superfície crítica. O estado só volta a **SATISFEITA** quando o commit final passar testes e lint dos flavors `public` e `privileged`, gerar release pública APK/AAB e gerar o artifact OEM separado. A funcionalidade PCM PSTN continuará marcada como **dependente de validação de hardware/OEM**, mesmo com o código compilado.

**Estado global: REABERTA — AGUARDANDO CI DOS FLAVORS.**
