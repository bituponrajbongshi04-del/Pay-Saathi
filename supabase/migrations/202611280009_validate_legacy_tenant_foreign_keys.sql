do $$
declare tbl text; c text;
begin
 foreach tbl in array array[
  'ps_wallets','ps_transactions','paysaathi_transactions','ps_refund_requests',
  'paysaathi_kyc_profiles','paysaathi_kyc_documents','ps_tickets','ps_ticket_messages',
  'paysaathi_support_tickets','paysaathi_support_messages','ps_notifications',
  'ps_ai_conversations','ps_ai_messages','ps_ai_decisions','ps_wallet_controls',
  'ps_wallet_adjustment_audit','ps_wallet_security_audit','ps_agent_activity',
  'ps_staff_directory','ps_staff_sessions','ps_staff_attendance','ps_staff_compensation',
  'ps_staff_payroll','ps_agent_presence','ps_payroll_owners'
 ] loop
  c:=tbl||'_tenant_id_fkey';
  if exists(select 1 from pg_constraint where conrelid=to_regclass('public.'||tbl) and conname=c and not convalidated) then
   execute format('alter table public.%I validate constraint %I',tbl,c);
  end if;
 end loop;
end $$;