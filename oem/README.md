# CallGuard AI — integração OEM / sistema

Esta pasta documenta a edição `privileged` do CallGuard. Ela **não é um APK normal para instalação pelo usuário** e não substitui a edição `public`.

## Por que existe

O Android/AOSP possui uma rota de sistema para triagem com áudio PSTN:

1. o app precisa atuar como discador padrão (`ROLE_DIALER` + `InCallService`);
2. `Call.enterBackgroundAudioProcessing()` coloca uma chamada `RINGING`/`ACTIVE` em `STATE_AUDIO_PROCESSING`;
3. `AudioManager.MODE_CALL_SCREENING` prepara o modo de áudio;
4. `AudioManager.getCallDownlinkExtractionAudioRecord()` fornece o áudio remoto para a IA;
5. `AudioManager.getCallUplinkInjectionAudioTrack()` injeta TTS/áudio da IA para o chamador;
6. `Call.exitBackgroundAudioProcessing(true)` apresenta a chamada aprovada ao usuário em `STATE_SIMULATED_RINGING`.

Os métodos de `Call` e os métodos de interceptação do `AudioManager` são `@SystemApi`/ocultos no SDK público. Os métodos de áudio exigem `android.permission.CALL_AUDIO_INTERCEPTION` e o hardware/Audio HAL precisa declarar o PSTN como interceptável.

## O que o flavor privileged faz

- applicationId de desenvolvimento: `com.callguard.ai.privileged`;
- declara `CALL_AUDIO_INTERCEPTION`, `RECORD_AUDIO`, `CALL_PHONE` e permissões de foreground/microfone;
- inclui `PrivilegedInCallService` e uma UI mínima que satisfaz a estrutura do papel de discador;
- usa reflexão somente na fronteira `@SystemApi`, porque o `android.jar` público não contém esses métodos;
- verifica em runtime:
  - se o CallGuard é o discador padrão;
  - se `CALL_AUDIO_INTERCEPTION` foi realmente concedida;
  - se `MODE_CALL_SCREENING` é suportado;
  - se as SystemApis estão presentes/acessíveis;
  - se `isPstnCallAudioInterceptable()` retorna `true`;
- em falha de preparação, tenta devolver a chamada ao usuário em vez de mantê-la invisível;
- usa PCM 16-bit mono a 16 kHz, frames de 20 ms, como contrato inicial para VAD/STT/TTS.

## Integração no sistema

A mera declaração da permissão no manifesto **não concede acesso**. O integrador do aparelho/ROM deve usar o mecanismo correto da plataforma para a versão Android e sua política de assinatura/permissões. Dependendo da integração isso pode envolver imagem de sistema, assinatura autorizada, configuração de papel/permissão privilegiada e/ou allowlist de `privapp`.

`privapp-permissions-callguard.xml.example` é apenas um modelo para a parte de allowlist; ele não substitui assinatura/política OEM nem garante acesso às APIs ocultas.

## Hidden/System API

O flavor atual usa reflexão e falha com `UnsupportedOperationException` caso a ROM bloqueie a SystemApi. Para um produto OEM, a opção preferível é o fabricante compilar a integração contra o **system SDK/API correspondente à ROM** ou fornecer uma API estável própria para o CallGuard. A reflexão existe para manter o repositório compilável com o SDK público e permitir prototipagem controlada.

## Segurança

A edição privilegiada não deve ser publicada na Play Store como se tivesse capacidade PSTN garantida. O artifact `CallGuardAI-privileged-oem` é sempre produzido como **unsigned** pelo CI, para evitar confusão com a release pública e porque a assinatura/política final pertence ao OEM/sistema.
