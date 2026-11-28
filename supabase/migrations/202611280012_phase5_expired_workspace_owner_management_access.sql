-- Phase 5: keep tenant owners able to manage an expired workspace without restoring data access.
begin;

create or replace function public.ps_tenant_list_available()
returns jsonb
language sql
stable
security definer
set search_path=public,pg_temp
as $$
 select coalesce(jsonb_agg(jsonb_build_object(
  'id',t.id,'slug',t.slug,'name',t.name,'status',t.status,
  'role',m.role,'is_default',m.is_default,
  'plan_key',s.plan_key,'subscription_status',s.status,'trial_ends_at',s.trial_ends_at
 ) order by m.is_default desc,t.created_at asc),'[]'::jsonb)
 from public.ps_tenant_memberships m
 join public.ps_tenants t on t.id=m.tenant_id and t.status='active'
 join public.ps_tenant_subscriptions s on s.tenant_id=t.id
 where m.user_id=auth.uid() and m.status='active'
 and (
   (m.role in ('owner','admin'))
   or (s.status in ('trial','active') and (s.status='active' or s.trial_ends_at is null or s.trial_ends_at>now()))
 );
$$;

create or replace function public.ps_tenant_set_active(p_tenant_id uuid)
returns boolean
language plpgsql
security definer
set search_path=public,pg_temp
as $$
declare v_role text; v_subscription_status text; v_trial_end timestamptz;
begin
 select m.role,s.status,s.trial_ends_at into v_role,v_subscription_status,v_trial_end
 from public.ps_tenant_memberships m
 join public.ps_tenants t on t.id=m.tenant_id and t.status='active'
 join public.ps_tenant_subscriptions s on s.tenant_id=t.id
 where m.tenant_id=p_tenant_id and m.user_id=auth.uid() and m.status='active'
 limit 1;
 if v_role is null then
  raise exception 'This business is unavailable or you are not a member' using errcode='42501';
 end if;
 if v_role not in ('owner','admin') and not (
  v_subscription_status in ('trial','active') and
  (v_subscription_status='active' or v_trial_end is null or v_trial_end>now())
 ) then
  raise exception 'This business subscription has expired' using errcode='42501';
 end if;
 update public.ps_tenant_memberships set is_default=false where user_id=auth.uid() and is_default=true;
 update public.ps_tenant_memberships set is_default=true where user_id=auth.uid() and tenant_id=p_tenant_id and status='active';
 return true;
end;
$$;

create or replace function public.ps_tenant_list_members()
returns jsonb
language plpgsql
stable
security definer
set search_path=public,pg_temp
as $$
declare v_tenant uuid;
begin
 select m.tenant_id into v_tenant
 from public.ps_tenant_memberships m
 join public.ps_tenants t on t.id=m.tenant_id and t.status='active'
 where m.user_id=auth.uid() and m.status='active' and m.is_default=true
 limit 1;
 if v_tenant is null or not public.ps_is_tenant_admin(v_tenant) then
  raise exception 'Business administrator access required' using errcode='42501';
 end if;
 return coalesce((
  select jsonb_agg(jsonb_build_object(
   'user_id',m.user_id,
   'full_name',coalesce(nullif(p.full_name,''),u.email),
   'email',u.email,
   'role',m.role,
   'status',m.status,
   'joined_at',m.created_at
  ) order by case m.role when 'owner' then 1 when 'admin' then 2 when 'staff' then 3 else 4 end,m.created_at)
  from public.ps_tenant_memberships m
  join auth.users u on u.id=m.user_id
  left join public.ps_profiles p on p.id=m.user_id
  where m.tenant_id=v_tenant
 ),'[]'::jsonb);
end;
$$;

revoke all on function public.ps_tenant_list_available() from public,anon;
revoke all on function public.ps_tenant_set_active(uuid) from public,anon;
revoke all on function public.ps_tenant_list_members() from public,anon;
grant execute on function public.ps_tenant_list_available() to authenticated;
grant execute on function public.ps_tenant_set_active(uuid) to authenticated;
grant execute on function public.ps_tenant_list_members() to authenticated;

commit;