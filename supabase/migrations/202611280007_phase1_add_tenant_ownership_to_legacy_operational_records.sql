-- Phase 1 of legacy-data tenant migration.
-- Add nullable tenant ownership to user-scoped operational records.
-- Existing records are mapped to each user's active default workspace.
-- Keep nullable until all historical/orphan records and every write path are audited.
begin;

do $$
declare item text[]; tbl text; col text; constraint_name text;
begin
  foreach item slice 1 in array array[
    array['ps_wallets','user_id'],
    array['ps_transactions','user_id'],
    array['paysaathi_transactions','user_id'],
    array['ps_refund_requests','user_id'],
    array['paysaathi_kyc_profiles','user_id'],
    array['paysaathi_kyc_documents','user_id'],
    array['ps_tickets','user_id'],
    array['paysaathi_support_tickets','customer_id'],
    array['ps_notifications','user_id'],
    array['ps_ai_conversations','user_id'],
    array['ps_ai_messages','user_id'],
    array['ps_ai_decisions','user_id'],
    array['ps_wallet_controls','user_id'],
    array['ps_wallet_adjustment_audit','user_id'],
    array['ps_wallet_security_audit','user_id'],
    array['ps_agent_activity','agent_id'],
    array['ps_staff_directory','user_id'],
    array['ps_staff_sessions','user_id'],
    array['ps_staff_attendance','staff_user_id'],
    array['ps_staff_compensation','staff_user_id'],
    array['ps_staff_payroll','staff_user_id'],
    array['ps_agent_presence','agent_id'],
    array['ps_payroll_owners','user_id']
  ]
  loop
    tbl := item[1]; col := item[2];
    if to_regclass('public.'||tbl) is null then continue; end if;
    execute format('alter table public.%I add column if not exists tenant_id uuid',tbl);
    constraint_name := tbl||'_tenant_id_fkey';
    if not exists(select 1 from pg_constraint where conname=constraint_name and conrelid=to_regclass('public.'||tbl)) then
      execute format('alter table public.%I add constraint %I foreign key (tenant_id) references public.ps_tenants(id) on delete restrict not valid',tbl,constraint_name);
    end if;
    execute format('create index if not exists %I on public.%I (tenant_id)',tbl||'_tenant_id_idx',tbl);
    execute format(
      'update public.%I x set tenant_id = (select m.tenant_id from public.ps_tenant_memberships m where m.user_id=x.%I and m.status=''active'' order by m.is_default desc, m.created_at asc limit 1) where x.tenant_id is null and x.%I is not null',
      tbl,col,col
    );
  end loop;
end $$;

-- Child records inherit ownership from their parent ticket, never from a
-- client-supplied tenant identifier.
alter table public.ps_ticket_messages add column if not exists tenant_id uuid;
alter table public.ps_ticket_messages add constraint ps_ticket_messages_tenant_id_fkey foreign key (tenant_id) references public.ps_tenants(id) on delete restrict not valid;
create index if not exists ps_ticket_messages_tenant_id_idx on public.ps_ticket_messages(tenant_id);
update public.ps_ticket_messages m set tenant_id=t.tenant_id from public.ps_tickets t where m.ticket_id=t.id and m.tenant_id is null;

alter table public.paysaathi_support_messages add column if not exists tenant_id uuid;
alter table public.paysaathi_support_messages add constraint paysaathi_support_messages_tenant_id_fkey foreign key (tenant_id) references public.ps_tenants(id) on delete restrict not valid;
create index if not exists paysaathi_support_messages_tenant_id_idx on public.paysaathi_support_messages(tenant_id);
update public.paysaathi_support_messages m set tenant_id=t.tenant_id from public.paysaathi_support_tickets t where m.ticket_id=t.id and m.tenant_id is null;

commit;