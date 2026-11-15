# Validação da interface

## Versão 0.8.0 — 15 de novembro de 2026

Ambiente: Windows, JDK 17, Gradle 9.4.1, Android SDK 36. A verificação estrita
de dependências continua ativa; nenhuma nova dependência foi necessária.

`assembleDebug assembleDebugAndroidTest lintDebug`: **passaram**. O lint
reportou zero erros e 14 avisos: seis de SDK/dependências e espaço livre já
existentes, e oito sugestões de usar a extensão KTX para SharedPreferences.
APK debug 0.8.0 (código 13), mínimo API 24 e alvo API 36. O código foi
incrementado a partir da 0.7.3 já presente em `main`.
Assinatura APK v2 verificada. SHA-256:
`16153ce99a8fd9a0bcc0fbc60d852bc48d699f90444f91d4e01f1a095a993dc4`.

`testDebugUnitTest`: **95 testes, 92 aprovados, 2 falhas e 1 ignorado**.
As falhas continuam sendo os dois casos de POSIX/symlink descritos abaixo;
o teste de compatibilidade de produção exige ZIPs externos. Os 13 novos
testes de transporte, retomada, cancelamento, velocidade e identidade da
versão passaram. A retomada lê o checkpoint novamente em uma nova instância.

No emulador Android 16 / API 36, 1080 × 2400, densidade 420 e fonte padrão:
**14/14 testes Android passaram**. São 12 cenários de interface com estados
controlados, um teste de preferências persistidas e um teste que inicia o
serviço real, coloca a Activity fora da tela e cancela a transferência.
Callbacks de uma geração cancelada são rejeitados. As capturas foram
inspecionadas visualmente.

Também passaram **4/4 cenários** em 840 × 1680 (320 × 640 dp), fonte a 130%:
status/atualização no catálogo, alteração do destino lembrado, diagnóstico
copiável e métricas de download/cancelamento. Os testes rolam até os controles
fora da área visível antes de interagir; o rodapé continua acessível.

O APK normal também foi aberto e consultou as Releases reais: JP `v20261111`,
NA `v20261110` e Blue Archive Global `v20261026`. A captura inicial do catálogo
vem dessa execução; as telas de atualização/instalação usam estados controlados.

Uma primeira rodada sofreu interferência de um ANR do System UI do emulador
durante concorrência com o build. Após liberar a janela e encerrar o Gradle,
a rodada completa passou, incluindo acesso ao clipboard. As capturas dessa
primeira rodada foram substituídas.

A proteção de assinatura/manifesto e o motor transacional continuam exigidos
antes da instalação. O serviço de download escreve somente no armazenamento
privado do aplicativo. As regras de retomada e comparação de versões estão
em [Catálogo, preferências e download](downloads-and-catalog.md).

Não houve instalação real em jogos, teste físico, encerramento forçado do
processo durante a transferência nem teste prolongado sob restrições de
bateria de fabricantes. A retomada HTTP foi exercitada com respostas
controladas; iniciar e cancelar o serviço é verificado separadamente no Android.

## Referência anterior — versão 0.7.2

Data: 14 de novembro de 2026.
Ambiente: Windows, JDK 17, Gradle 9.4.1, Android SDK 36.

## Build

`gradle lint assembleDebug assembleDebugAndroidTest` passou com a verificação
estrita de dependências ativa. O lint não encontrou erros; os seis avisos
referem-se ao SDK/dependências existentes e ao uso de `File.usableSpace`
no motor de arquivos. As dependências de teste foram adicionadas ao catálogo
de SHA-256, sem alterar ou remover hashes existentes.

## Testes de interface

Nove cenários instrumentados em `InstallerFlowTest`:

- Catálogo e preparação de acesso com uma ação principal.
- Autorização, região e destino independente do BetterFGO.
- Blue Archive restrito ao servidor Global.
- Voltar entre as etapas e retornar ao catálogo.
- Perda de autorização devolvendo o usuário à etapa de acesso.
- Navegação bloqueada durante download e ausência de cancelamento no commit.
- Instalação condicionada ao jogo fechado e às capacidades, inclusive em atualização.
- Tradução instalada com atualização disponível e confirmação de desinstalação.
- Falha de rede com nova tentativa e bloqueio de instalação com recibo pendente.

Executados no Android 16 / API 36, 1080 × 2400, densidade 420, fonte padrão:
**9/9 passaram**. Também passaram **9/9** em 840 × 1680 (320 × 640 dp),
com fonte do sistema em 130%. O conteúdo rola e a ação principal permanece
acessível fora da rolagem. As capturas foram inspecionadas visualmente.

Para reproduzir, com um emulador conectado:

```sh
gradle assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r dev.translate.installer.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/dev.translate.installer/files/screenshots/ ./screenshots
```

Os testes usam callbacks e estados controlados. Não exercitam o download real,
a autorização real do Shizuku ou a escrita de arquivos em jogos instalados.
O APK normal também foi aberto diretamente no emulador para verificar o catálogo.

## Suíte unitária existente

`testDebugUnitTest`: 82 testes, **79 passaram**, **2 falharam por limitações
do Windows**, **1 ignorado** por ausência dos ZIPs públicos de produção.
Os arquivos de teste e o motor coberto por esses testes não foram alterados.

As duas falhas em `TransactionalFileEngineTest` foram:

- `overwrites existing targets without creating artifacts and preserves mode`:
  `UnsupportedOperationException` ao definir permissões POSIX (linha 63).
- `Blue Archive rejects a staging symlink without touching either database`:
  `FileSystemException` ao criar symlink sem o privilégio necessário (linha 439).

A suíte completa ainda precisa ser confirmada em Linux. O WSL instalado
nesta máquina não inicia porque o suporte de virtualização está indisponível.
Nenhum teste foi desabilitado para esconder essas falhas.

## Limites

Não houve instalação real em Fate/Grand Order ou Blue Archive, nem teste
físico em Android 7–9. A política de acesso legado e as verificações do motor
foram preservadas. O fluxo real com Shizuku e arquivos de jogo deve ser
validado antes de publicar uma nova versão do aplicativo.
