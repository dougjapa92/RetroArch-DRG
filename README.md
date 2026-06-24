# RetroArch DRG

O RetroArch DRG é a versão customizada e otimizada do frontend de referência para a API libretro.
Este projeto foca em melhorar a experiência do usuário com automatizações e correções específicas.

## Modificações e Melhorias

Este projeto inclui as seguintes modificações em relação ao RetroArch original:

- **Autoconfiguração de Controles**: Implementação de um sistema de autoconfiguração inteligente que detecta e configura controles automaticamente ao serem conectados.
- **Correção para Controles Genéricos**: Ajustes e mapeamentos específicos para garantir que controles genéricos (frequentemente problemáticos) funcionem corretamente sem configuração manual exaustiva.
- **Pré-configuração por Dispositivo**: Otimizações automáticas de acordo com o hardware detectado, garantindo o melhor desempenho e compatibilidade desde a primeira execução.
- **Unificação de Autoconfiguração**: Simplificação do sistema de autoconfiguração, removendo legados desnecessários para maior estabilidade.
- **Mapeamento Duplo (Dual Binding)**: Suporte para configurar até dois botões físicos ou teclas de teclado para a mesma ação no RetroPad, permitindo maior flexibilidade em controles customizados ou teclados.

## Sobre o RetroArch

O RetroArch é um frontend para emuladores, motores de jogo e reprodutores de mídia.
Ele permite que você execute jogos clássicos em uma ampla gama de computadores e consoles através de sua interface gráfica elegante. As configurações também são unificadas, portanto, a configuração é feita de uma vez por todas.

![XMB menu driver](docs/XMB-main-menu.jpg "XMB menu driver")

## libretro

[libretro](https://www.libretro.com) é uma API que expõe callbacks genéricos de áudio/vídeo/entrada.
Um frontend para libretro (como o RetroArch) lida com saída de vídeo, saída de áudio, entrada e ciclo de vida da aplicação.

## Suporte

Para entrar em contato com os desenvolvedores ou obter suporte técnico, utilize os canais oficiais do projeto original ou abra uma issue neste repositório.

## Documentação

Veja o nosso [Centro de Documentação](https://docs.libretro.com/). No Unix, as páginas de manual são fornecidas.
Mais material focado em desenvolvedores pode ser encontrado [aqui](https://docs.libretro.com/development/libretro-overview/).

## Filosofia

O RetroArch DRG mantém a filosofia de ser leve e eficiente, enquanto adiciona recursos que facilitam a vida do usuário final, especialmente no que diz respeito à configuração de periféricos e otimização de sistema.

## Como Clonar o Repositório

Para clonar este repositório e seus submódulos necessários, use o seguinte comando:

```bash
git clone --recursive https://github.com/dougretrogames/RetroArch-DRG.git
```

Se você já clonou o repositório sem os submódulos, pode inicializá-los com:

```bash
git submodule update --init --recursive
```
