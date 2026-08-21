# Privacidade e dados — requisitos do produto

Este documento define requisitos técnicos de privacidade do CallGuard AI. Não é parecer jurídico.

## Dados que o produto pode tratar

Quando a integração de áudio real existir, o fluxo poderá envolver:

- número de telefone do chamador;
- horário e metadados básicos da chamada;
- áudio recebido durante a triagem;
- transcrição da fala;
- nome declarado pelo chamador;
- motivo/resumo da ligação;
- decisão e confiança do classificador.

Esses dados podem identificar pessoas e devem ser tratados como dados pessoais.

## Princípios de projeto

O desenho deve seguir finalidade, necessidade/minimização, transparência, segurança e prevenção:

- coletar apenas o necessário para decidir a triagem;
- não enviar áudio/transcrição a servidores no modo offline;
- não guardar áudio bruto por padrão;
- guardar transcrição/histórico somente quando necessário à experiência do usuário;
- permitir exclusão clara;
- manter retenção configurável antes de produção;
- evitar logs com número, nome ou transcrição;
- proteger dados persistidos e impedir backup inadvertido;
- não reutilizar ligações reais para treino sem opt-in específico e anonimização.

## Estado técnico atual

- o APK não declara permissão `INTERNET`;
- backup Android está desabilitado;
- histórico é cifrado com AES-GCM e chave do Android Keystore;
- o usuário pode apagar o histórico local;
- exemplos iniciais são marcados como DEMO;
- nenhuma ponte de áudio real está implementada, portanto o MVP atual não grava chamadas da operadora.

## Aviso ao chamador

Antes de uma release que realmente converse com chamadores, deve existir uma saudação curta e clara informando que a chamada está sendo atendida/triada por um sistema automatizado e, conforme a funcionalidade habilitada e os requisitos jurídicos aplicáveis, que a fala pode ser transcrita localmente para decidir o encaminhamento.

O texto final e a base jurídica precisam ser revisados para o mercado de distribuição. O produto não deve depender de uma política obscura ou apenas de termos longos dentro do app.

## Retenção proposta para produção

Configuração a validar em pesquisa/testes:

- áudio bruto: não persistir por padrão;
- transcrição de chamada bloqueada: retenção curta ou somente resumo, configurável;
- chamada permitida: histórico configurável pelo usuário;
- botão de apagar tudo;
- opção futura de apagar automaticamente após N dias.

## Fine-tuning com dados reais

Chamadas reais não entram automaticamente em dataset. Para uso em melhoria de modelo:

1. consentimento/opt-in separado;
2. minimização e anonimização;
3. remoção de telefone, nomes e outros identificadores não necessários;
4. controle de origem e finalidade;
5. política de retenção;
6. processo de exclusão;
7. revisão de conformidade antes de qualquer coleta em escala.

## Antes de distribuição no Brasil

A release deve revisar, no mínimo:

- LGPD e papel dos agentes de tratamento;
- transparência/finalidade/necessidade/segurança;
- tratamento de voz/transcrição e aviso ao chamador;
- política de privacidade acessível;
- requisitos da loja/canal de distribuição e do papel de call screening;
- processo para incidentes e suporte ao titular quando aplicável.

A ANPD publica guias de segurança da informação e materiais orientativos que devem fazer parte dessa revisão de release.
