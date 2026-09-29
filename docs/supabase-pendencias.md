# Pendências no Supabase

Nada deste arquivo foi executado. São as alterações necessárias para completar funcionalidades que já estão prontas na interface do preview. Revise e aplique primeiro num banco de testes, nunca direto em produção.

O preview continua com `PREVIEW_READ_ONLY = true` (em `app.js`), então nenhuma destas alterações é usada por ele enquanto o bloqueio estiver ativo.

## 1. Histórico de atividades (obrigatório para o log de auditoria)

A tela **Atividades** e a linha do tempo do processo já funcionam com um histórico *reconstruído* a partir das datas dos registros (`created_at`, `updated_at`, `completed_at`, `paid_at`, `sent_at`). Esse histórico reconstruído tem três limitações:
- não mostra exclusões;
- não mostra o autor de cada edição;
- mostra só a última alteração de cada registro.

Para gravar o histórico real:

```sql
create table public.activity_logs (
  id          uuid primary key default gen_random_uuid(),
  firm_id     uuid not null references public.law_firms(id) on delete cascade,
  actor_id    uuid not null references auth.users(id),
  action      text not null,          -- ex.: client.created, deadline.completed, document.deleted
  entity_type text not null,          -- client, case, movement, deadline, task, event, document, finance, template, lead, checklist
  entity_id   uuid,                   -- pode apontar para um registro já excluído
  summary     text not null default '',
  metadata    jsonb not null default '{}'::jsonb,  -- case_id, fields (campos alterados), status_from/status_to, amount
  created_at  timestamptz not null default now()
);

create index activity_logs_firm_created_idx on public.activity_logs (firm_id, created_at desc);
create index activity_logs_case_idx on public.activity_logs ((metadata->>'case_id'));

alter table public.activity_logs enable row level security;

-- Membros ativos do escritório podem ler o histórico do próprio escritório.
create policy "activity_logs_select_members" on public.activity_logs
  for select using (
    exists (select 1 from public.firm_members m
            where m.firm_id = activity_logs.firm_id and m.user_id = auth.uid() and m.status = 'active')
  );

-- Cada usuário só grava registros em seu próprio nome e no próprio escritório.
create policy "activity_logs_insert_self" on public.activity_logs
  for insert with check (
    actor_id = auth.uid() and exists (
      select 1 from public.firm_members m
      where m.firm_id = activity_logs.firm_id and m.user_id = auth.uid() and m.status = 'active')
  );

-- Sem policies de update/delete: o histórico não pode ser alterado nem apagado pelo app.
```

Depois de criar a tabela, mude `const ACTIVITY_LOG_ENABLED = false;` para `true` em `app.js`. Todas as gravações do app já chamam `logActivity(...)`, que nunca interrompe a ação principal se o registro falhar.

**Alternativa mais robusta (opcional):** gravar o log por *triggers* no banco (`after insert/update/delete` em cada tabela). Assim o histórico não depende do frontend e registra também alterações feitas fora do app.

## 2. Nomes dos responsáveis e do advogado

Hoje o app só consegue mostrar "Você" ou "Outro membro", porque não há uma tabela com os nomes dos usuários. O campo `{{advogado_nome}}` do gerador é digitado à mão e lembrado neste navegador.

Sugestão:

```sql
alter table public.firm_members add column display_name text;
alter table public.firm_members add column oab_number text;   -- opcional, útil em documentos
```

Com isso é possível:
- mostrar o nome real do responsável em prazos, tarefas, agenda, linha do tempo e atividades;
- preencher `{{advogado_nome}}` automaticamente, e opcionalmente criar uma variável `{{advogado_oab}}`.

## 3. Verificações (não são alterações, só confirmar)

| Item | Por quê |
|---|---|
| Existe policy de **DELETE** em `office_templates` para membros do escritório | O botão "Excluir" dos modelos usa `delete()` |
| O bucket `case-documents` aceita `text/plain` | "Salvar no sistema" do gerador envia o documento como `.txt` |
| As tabelas têm `updated_at` atualizado por trigger | O histórico reconstruído usa `updated_at` para detectar alterações |
| `deadlines.assigned_to` existe | A central de prazos mostra `assigned_to` e, se ele não existir, usa `created_by` |
| As policies de `SELECT` permitem ler a linha recém-inserida | Os inserts agora usam `.select("id").single()` para registrar o ID no histórico |

## 4. Envio automático de alertas (não implementado)

Os alertas de prazos aparecem **somente dentro do sistema**. Um aviso por WhatsApp ou e-mail exigiria uma Edge Function agendada (cron), e ficou fora deste escopo de propósito.
