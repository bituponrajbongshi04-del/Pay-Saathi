-- Phase 2: enforce tenant isolation for legacy operational data.
-- Existing UI inserts may omit tenant_id; the trigger supplies the user's
-- active/default tenant and rejects cross-tenant writes.
begin;

create or replace function public.ps_enforce_row_tenant()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare active_tenant uuid;
begin
  -- Trusted backend/service-role calls may intentionally perform platform work.
  -- Authenticated browser clients must always be bound to one active tenant.
  if auth.uid() is null then
    return new;
  end if;

  active_tenant := public.ps_current_tenant_id();
  if active_tenant is null then
    raise exception 'No active business workspace is selected' using errcode='42501';
  end if;

  if tg_op = 'INSERT' then
    if new.tenant_id is null then
      new.tenant_id := active_tenant;
    elsif new.tenant_id <> active_tenant then
      raise exception 'Cross-business record creation is not allowed' using errcode='42501';
    end if;
  else
    if old.tenant_id is distinct from active_tenant
       or new.tenant_id is distinct from old.tenant_id then
      raise exception 'Cross-business record changes are not allowed' using errcode='42501';
    end if;
  end if;
  return new;
end;
$$;

revoke all on function public.ps_enforce_row_tenant() from public, anon, authenticated;

do $$
declare tbl text;
begin
  foreach tbl in array array[
    'ps_wallets','ps_transactions','paysaathi_transactions','ps_refund_requests',
    'paysaathi_kyc_profiles','paysaathi_kyc_documents','ps_tickets','ps_ticket_messages',
    'paysaathi_support_tickets','paysaathi_support_messages','ps_notifications',
    'ps_ai_conversations','ps_ai_messages','ps_ai_decisions','ps_wallet_controls',
    'ps_wallet_adjustment_audit','ps_wallet_security_audit','ps_agent_activity',
    'ps_staff_directory','ps_staff_sessions','ps_staff_attendance','ps_staff_compensation',
    'ps_staff_payroll','ps_agent_presence','ps_payroll_owners'
  ]
  loop
    execute format('drop trigger if exists enforce_row_tenant on public.%I',tbl);
    execute format('create trigger enforce_row_tenant before insert or update on public.%I for each row execute function public.ps_enforce_row_tenant()',tbl);
    execute format('drop policy if exists tenant_isolation_guard on public.%I',tbl);
    execute format(
      'create policy tenant_isolation_guard on public.%I as restrictive for all to authenticated using (tenant_id = public.ps_current_tenant_id()) with check (tenant_id = public.ps_current_tenant_id())',
      tbl
    );
  end loop;
end $$;

commit;