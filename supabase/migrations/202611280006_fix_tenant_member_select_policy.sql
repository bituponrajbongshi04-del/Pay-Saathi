-- Correct tenant-row visibility: the membership tenant_id must match the tenant id.
drop policy if exists ps_tenants_member_read on public.ps_tenants;
create policy ps_tenants_member_read
on public.ps_tenants
for select
to authenticated
using (
  public.ps_is_platform_owner()
  or exists (
    select 1
    from public.ps_tenant_memberships m
    where m.tenant_id = ps_tenants.id
      and m.user_id = auth.uid()
      and m.status = 'active'
  )
);
