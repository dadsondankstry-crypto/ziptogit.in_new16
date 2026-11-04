# 🛡️ Guardião de Dados — versão 1.0 (Android nativo)

**Um APK de diagnóstico e bloqueio local**, feito para economizar a franquia de internet do telefone.
Não precisa PWA, website, conta externa, login, WhatsApp, token nem servidor.

## O que funciona nesta versão

- Mostrar **velocidade atual de download/upload do celular inteiro** (amostragem a cada 2 s, sem identificar o app responsável).
- Consultar histórico de tráfego **Wi‑Fi** e **dados móveis** por UID (aplicativo) nos períodos de 24 horas, 7 dias e 30 dias, conforme disponibilidade do sistema.
- Exibir lista ordenada de consumidores, inclusive serviços de sistema quando o Android fornecer seus dados.
- Buscar aplicativos instalados pelo nome ou pacote na tela Bloqueio.
- Marcar e desmarcar apps do usuário; salvar a seleção localmente no aparelho. Um botão opcional marca apps de usuário exceto ChatGPT, com confirmação.
- Com a autorização do Android, **bloquear a conexão dos aplicativos selecionados** utilizando uma VPN *local* que lê e descarta seus pacotes sem enviar dados a servidor algum.
- Ativar e desativar o bloqueio, inclusive pela notificação persistente.
- Exportar relatório do histórico de uso em CSV, salvo onde o usuário escolher.
- Ícone próprio e tela em português.

## Limitações importantes (não são erros de instalação)

1. O histórico do Android **não é um analisador de tráfego ao vivo**. Estatísticas são registradas em intervalos, com atraso. Não é possível concluir que um pico de 130 KB/s veio de determinado app só por esse relatório.
2. `NetworkStatsManager` pode omitir dados ou compartilhá-los entre apps com mesmo UID, dependendo do fabricante, perfil e versão do Android. Wi‑Fi da rede do navio é identificado como Wi‑Fi, não como dados móveis.
3. O bloqueador usa a API `VpnService`: **ele descarta pacotes somente dos apps marcados**, e não é VPN de acesso à internet. Apps não marcados seguem com sua conexão normal. Não use simultaneamente outra VPN.
4. O Android pode manter serviços de sistema com acesso à rede. Por segurança, eles não aparecem na seleção do bloqueio; isso não é um modo universal para deixar *somente o ChatGPT* com internet.
5. VPN consome um pouco de bateria enquanto ativa. Quando o Android interrompe o serviço, bloqueio para. O aplicativo não promete funcionamento permanente.
6. Um plano com WhatsApp liberado é uma política do provedor: o aplicativo **não permite converter franquia de WhatsApp em internet irrestrita**, nem imita outro app para burlar a cobrança.
7. A notificação de VPN deve permanecer enquanto está ativa. Se os apps escolhidos incluírem mensageiros, eles não receberão mensagens até desativar.
8. Build em GitHub Actions é um **APK de depuração** (`app-debug.apk`), não um APK assinado para publicar na Play Store.

## Passo a passo pelo celular — gerar APK no GitHub

**Não extraia nem recomprima o ZIP.** O ZIP já está preparado com os arquivos na raiz, incluindo `.github/workflows/main.yml`.

1. Crie um repositório **novo** no GitHub com o nome `Guardiao-de-Dados` e branch `main`. Para simplificar, inicialize com README (se o site exigir), mas você pode usar um repositório vazio se seu aplicativo de envio suportar isso.
2. No seu aplicativo de enviar ZIP para o GitHub, autentique com um token autorizado; selecione esse repositório novo, a branch `main`, e o arquivo ZIP **inteiro**.
3. Confira na raiz do repositório se aparecem `app`, `.github/workflows`, `build.gradle` e `settings.gradle`. Caso haja uma pasta única extra, o ZIP foi enviado com uma pasta raiz a mais: reenvie no formato correto.
4. Abra o repositório, entre em **Actions** e depois no fluxo **Gerar APK - Guardiao de Dados**. O envio normalmente dispara a compilação automaticamente. Se não, escolha **Run workflow**.
5. Quando a compilação terminar com símbolo ✅, entre na execução e role até **Artifacts**. Baixe **Guardiao-de-Dados-APK** e extraia o `app-debug.apk`.
6. Instale o APK no Android. Se o aparelho perguntar, autorize a instalação apenas porque você conhece este projeto; confira as permissões do app.

## Configuração no Android

- Abra **Guardião de Dados → Consumo → Dar acesso**. No painel de Ajustes do Android, localize Guardião de Dados e permita **Acesso aos dados de uso**. Volte ao app e toque **Atualizar**.
- Para testar de forma controlada, vá à aba **Bloqueio**, pesquise um aplicativo comum que você não precise no momento, marque e toque **Ativar bloqueio**. O Android exibirá um aviso de VPN; aceite se concordar.
- Tente abrir a internet **somente no app marcado** (ele deve falhar). Teste um segundo app não marcado (deve continuar funcionando).
- Toque **Desligar bloqueio** antes de passar a outra VPN ou sair de testes. O botão de parar também está na notificação.

## Permissões solicitadas

- `PACKAGE_USAGE_STATS` (especial, concedida pelo usuário) — relatórios históricos.
- `QUERY_ALL_PACKAGES` — descoberta dos apps para seleção; para distribuição pela Play Store seria preciso passar pela política de visibilidade de pacotes.
- `BIND_VPN_SERVICE` — exigida pelo Android na declaração do serviço de VPN. A autorização é exibida pelo próprio Android.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` — serviço de bloqueio visível e controlável.
- `POST_NOTIFICATIONS` — notificação do bloqueio, quando necessário.
- **Não solicita permissão `INTERNET`** e não possui integração com serviços remotos.

## Sobre possíveis erros de compilação

Este projeto foi gerado com Android Gradle Plugin 8.9.2 / Gradle 8.11.1 / Java 17 / SDK 35 e inclui workflow para Linux Ubuntu.
Por não haver SDK Android e Gradle completos disponíveis no ambiente em que ele foi gerado, **não foi possível compilar nem testar num telefone aqui**. Se o GitHub mostrar ❌, abra o job e envie as últimas linhas do erro para corrigir o projeto sem adivinhações.

## Privacidade e segurança

Dados de consumo vêm do próprio Android. Não há envio de telemetria, rastreamento de sites, contas ou senhas. O aplicativo não requer root e não altera configurações do provedor ou do Wi‑Fi do navio.

Copyright (c) 2026. Código de exemplo fornecido para testes pessoais e aprimoramento.
