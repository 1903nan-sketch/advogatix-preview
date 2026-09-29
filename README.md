# AdvogaTix Preview

Ambiente de visualização do AdvogaTix para testar alterações antes de enviar para produção.

- Este repositório não está conectado à Vercel.
- O frontend é publicado pelo GitHub Pages.
- As alterações aprovadas devem ser levadas ao repositório principal `1903nan-sketch/advogatix`.

## Modo somente leitura

O preview ainda usa o **mesmo Supabase da produção** (`llxquroiaehemebuikwg`). Para que ele nunca altere dados reais, `app.js` define `PREVIEW_READ_ONLY = true`, que:

- bloqueia `insert`, `update`, `upsert` e `delete` em todas as tabelas, além de `rpc`;
- bloqueia upload, remoção e movimentação de arquivos no Storage (download continua liberado);
- bloqueia todas as chamadas de Edge Functions, inclusive o envio e a conexão do WhatsApp;
- mostra uma faixa "Preview somente leitura" no topo da tela.

O login e a leitura dos dados continuam funcionando. Ao levar o código para o repositório principal, use `PREVIEW_READ_ONLY = false`. Só desligue o bloqueio aqui depois de apontar o preview para um banco Supabase separado.

## Observações

- Datas e horas são digitadas e exibidas sempre no horário de Brasília (`America/Sao_Paulo`), independentemente do fuso do aparelho.
- As bibliotecas externas têm versão fixa e hash de integridade (SRI) em `index.html`. Ao atualizar uma versão, recalcule o hash.

## Estrutura

- `app.js`: núcleo. Faz a conexão com o Supabase, com o bloqueio de preview, e cuida de autenticação, clientes, processos, movimentações, WhatsApp e do registro de atividades (`logActivity`).
- `organizer.js`: prazos (central por vencimento), agenda, tarefas, financeiro, documentos, modelos, CRM, relatórios, calendário e pesquisa universal.
- `features.js`: linha do tempo do processo, alertas de prazos no painel, gerador de documentos e tela de Atividades.
- `sw.js`: service worker da PWA. Ao alterar arquivos principais, aumente `ASSET_VERSION` e o `?v=` em `index.html`.
- `docs/supabase-pendencias.md`: tabelas, colunas e policies necessárias no Supabase, ainda **não aplicadas**.

## Histórico de atividades

`ACTIVITY_LOG_ENABLED = false` em `app.js` enquanto a tabela `activity_logs` não existir. Nesse modo, a tela Atividades mostra um histórico reconstruído a partir das datas dos registros.
