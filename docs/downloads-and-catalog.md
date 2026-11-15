# Catálogo, preferências e download — 0.8.0

## O que muda para o usuário

- O catálogo mostra as versões disponíveis para JP, NA e Blue Archive Global,
  a versão instalada registrada e a existência de uma atualização para o
  destino lembrado, incluindo BetterFGO.
- O botão de consulta atualiza as versões manualmente. O catálogo usa um cache
  de dez minutos para reduzir consultas e limites da API do GitHub. Falhas de
  consulta são mostradas, sem afirmar que uma versão desconhecida está atualizada.
- Região e cliente ficam salvos separadamente por jogo. A instalação continua
  exigindo autorização atual e confirmação de jogo fechado. Uma escolha salva
  pode ser alterada no resumo, e não representa uma permissão salva.
- O download mostra bytes, tamanho total, velocidade suavizada e estimativa.
  Bytes de um arquivo parcial recuperado não contam como velocidade da conexão.
- Um serviço Android em primeiro plano mantém a transferência com o aplicativo
  fora da tela, com notificação e cancelamento. A notificação abre o jogo
  correspondente. A autorização para notificações é solicitada uma vez, no
  primeiro download; negar essa permissão não impede o serviço de transferência.
- “Copiar diagnóstico” reúne versão do aplicativo, Android, perfil, acesso,
  etapa, códigos de erro e as dez últimas operações. Não copia caminhos,
  informações de conta, credenciais ou textos arbitrários de exceções.

## Retomada e integridade

O transporte mantém um único serviço ativo e grava arquivos exclusivamente no
diretório privado `resumable_downloads`. Um mutex coordena a gravação, a cópia
para verificação e a limpeza. Cada solicitação tem uma geração: callbacks de
uma transferência cancelada não podem restaurar o estado anterior nem o seu
progresso depois de um novo pedido.

O arquivo parcial é identificado por perfil de pacote e pela identidade exata
do asset (ID, nome, tamanho e tag). A retomada exige um ETag forte e usa `Range`
e `If-Range`. São aceitos somente intervalos coerentes com o tamanho publicado.
Se o servidor responde com o arquivo completo, a gravação reinicia do zero.
Mudanças de pacote, intervalos inválidos e HTTP 416 descartam o parcial.
Sem um validador forte, um download incompleto também reinicia do zero.

Há até três tentativas automáticas para interrupções de transporte. Após isso,
o download fica salvo para uma nova tentativa pelo usuário. Em uma reabertura,
o jogo e o destino da transferência pendente são recuperados. Forçar a parada
do aplicativo pelo Android impede execução automática; a retomada ocorre ao
abrir o aplicativo e solicitar a continuação. O serviço atende ao timeout do
Android 15+ e libera seu bloqueio de energia ao encerrar.

Antes de reutilizar o parcial, a Release é consultada novamente. O SHA-256 é
calculado sobre o arquivo inteiro, incluindo o prefixo reaproveitado. Depois,
uma cópia privada passa pelo verificador existente de assinatura, manifesto,
estrutura e hashes de arquivos. O serviço de download não acessa os diretórios
dos jogos e não instala nada em segundo plano.

O instalador mantém sua consulta de atualização imediatamente antes da
instalação. O cache do catálogo nunca autoriza a instalação de um pacote.

## Comparação de versões

A versão do manifesto assinado não precisa ter o mesmo texto da tag do GitHub.
Por isso, novas instalações registram também o ID imutável do asset utilizado,
associado ao destino e à versão do recibo. O catálogo só afirma que existe uma
atualização quando essa identidade pode ser comparada com a Release atual.
Recibos de versões antigas continuam válidos; sem o ID registrado, mostram a
versão instalada e permitem consultar a versão disponível, sem inventar um
resultado de comparação. Oficial e BetterFGO permanecem independentes.

## Validação

Testes de transporte cobrem interrupção, retomada, resposta sem suporte a Range,
mudança de Release/ETag, intervalo inválido, HTTP 416, cache completo, rejeição
de redirecionamento e propagação de cancelamento. Testes de catálogo verificam
a comparação por asset e o cálculo de velocidade/estimativa.

Os testes Android verificam preferências persistidas por destino, status no
catálogo, alteração de uma escolha lembrada, cópia de diagnóstico e a
inicialização/cancelamento do serviço real com a Activity fora da tela.
O teste do serviço não instala arquivos nos jogos.

As validações completas de instalação física, variantes Android 7–9 e os limites
de longa duração de cada fabricante continuam necessárias antes de publicar
uma Release de produção. As duas limitações POSIX/symlink do Windows estão
descritas no [relatório de validação](validation.md).
