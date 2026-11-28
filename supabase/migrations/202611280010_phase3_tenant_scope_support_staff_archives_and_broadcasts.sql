-- Phase 3: cover support staff, archived support records and tenant broadcasts.
begin;
do $$
declare item text[]; tbl text; col text; cname text;
begin
 foreach item slice 1 in array array[
  array['paysaathi_support_staff','user_id'],
  array['ps_tickets_archive','user_id'],
  array['ps_ticket_messages_archive','ticket_id'],
  array['paysaathi_notifications','created_by']
 ] loop
  tbl:=item[1]; col:=item[2];
  if to_regclass('public.'||tbl) is null then continue; end if;
  execute format('alter table public.%I add column if not exists tenant_id uuid',tbl);
  cname:=tbl||'_tenant_id_fkey';
  if not exists(select 1 from pg_constraint where conrelid=to_regclass('public.'||tbl) and conname=cname) then
   execute format('alter table public.%I add constraint %I foreign key (tenant_id) references public.ps_tenants(id) on delete restrict not valid',tbl,cname);
  end if;
  execute format('create index if not exists %I on public.%I (tenant_id)',tbl||'_tenant_id_idx',tbl);
  if tbl='ps_ticket_messages_archive' then
   update public.ps_ticket_messages_archive m set tenant_id=t.tenant_id from public.ps_tickets_archive t where m.ticket_id=t.id and m.tenant_id is null;
  else
   execute format(
    'update public.%I x set tenant_id=(select m.tenant_id from public.ps_tenant_memberships m where m.user_id=x.%I and m.status=''active'' order by m.is_default desc,m.created_at asc limit 1) where x.tenant_id is null and x.%I is not null',
    tbl,col,col
   );
  end if;
  execute format('drop trigger if exists enforce_row_tenant on public.%I',tbl);
  execute format('create trigger enforce_row_tenant before insert or update on public.%I for each row execute function public.ps_enforce_row_tenant()',tbl);
  execute format('drop policy if exists tenant_isolation_guard on public.%I',tbl);
  execute format('create policy tenant_isolation_guard on public.%I as restrictive for all to authenticated using (tenant_id=public.ps_current_tenant_id()) with check (tenant_id=public.ps_current_tenant_id())',tbl);
 end loop;
end $$;
commit;