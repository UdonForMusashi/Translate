# Interface do instalador

## Direção

O catálogo responde “qual jogo?”; o instalador responde “o que faço agora?”.
Uma etapa por vez, uma ação principal persistente e linguagem de jogador.
Identidade derivada do `fate-cafe-react`: superfícies de café, creme, dourado
e títulos serifados. As ilustrações identificam os jogos, sem competir com
os controles. Nenhuma imagem depende da rede durante o uso.

Paleta: fundo `#1B1513`, superfície `#312824`, linha `#51423A`, texto
`#F4E9DA`, secundário `#BFAF9F`, ação `#D4A373`.
Tipografia: serifa nativa do Android para títulos; sans nativa para controles e leitura.
Ritmo de 4/8 dp, margens de 24 dp, ações com pelo menos 48 dp.

```text
Catálogo                 Instalador
logo             ajuda   voltar / jogo
Suas histórias.          ilustração compacta
Em português.            Acesso > Versão > Instalar
[arte | FGO         ]    título da etapa
[arte | Blue Archive]    controles da etapa atual
Como funciona            ajuda e detalhes recolhidos
comunidade               ação principal persistente
```

## Revisão do plano

A identidade de café vem da marca existente, e não de uma paleta genérica.
Substituímos a pilha de cartões numerados por uma sequência real de três
etapas. Não há métricas decorativas, títulos em caixa alta nem animações
automáticas. Logs, caminhos e códigos ficam em detalhes técnicos.

## Fluxo e invariantes

- Acesso: instruções adequadas ao Android, autorização e recuperação de erro.
- Versão: região e cliente oficial/BetterFGO, preservando destinos separados.
- Instalar: resumo, download autenticado, confirmação de jogo fechado,
  instalação e resultado. Falhas preservam a possibilidade de tentar novamente.
- Voltar navega entre etapas; durante operações, não abandona a transação.
- Perder autorização devolve o usuário ao acesso. A UI nunca substitui as
  verificações de autorização, assinatura, hash, capacidade ou recibo.
- Desinstalar exige confirmação e explica que o jogo precisará baixar arquivos.
- Conteúdo rola; a ação principal permanece fora da rolagem, respeitando insets.
- Componentes selecionáveis têm semântica de rádio/checkbox e alvos de 48 dp.
- Layout centralizado de até 640 dp e rolagem para telas pequenas/fontes grandes.
- Ilustração apenas no catálogo e no acesso; as etapas seguintes priorizam os controles.

## Referências locais

- `fate-cafe-react/src/index.css`: tokens `vn-*` e contraste das superfícies.
- `fate-cafe-react/src/pages/home/HomeReadingEntry.tsx`: hierarquia e verbos.
- Logo já distribuído neste aplicativo.
- `BaixanteCafe/app.py`: consistência da marca e uso do dourado na ação principal.

Proveniência das imagens e fontes: [assets.md](assets.md).
