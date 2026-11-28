-- Phase 4: tenant-scoped team management using existing active PaySaathi accounts.
begin;

create or replace function public.ps_tenant_list_members()
returns jsonb
language plpgsql
stable
security definer
set search_path=public,pg_temp
as $$
declare v_tenant uuid;
begin
 v_tenant:=public.ps_current_tenant_id();
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

create or replace function public.ps_tenant_add_member(p_email text,p_role text default 'staff')
returns uuid
language plpgsql
security definer
set search_path=public,pg_temp
as $$
declare
 v_tenant uuid;
 v_actor_role text;
 v_user_id uuid;
 v_role text:=lower(trim(coalesce(p_role,'staff')));
 v_email text:=lower(trim(coalesce(p_email,'')));
 v_max_users integer;
 v_used integer;
 v_membership uuid;
begin
 v_tenant:=public.ps_current_tenant_id();
 if v_tenant is null then raise exception 'No active business workspace' using errcode='42501'; end if;
 select role into v_actor_role from public.ps_tenant_memberships
  where tenant_id=v_tenant and user_id=auth.uid() and status='active';
 if v_actor_role not in ('owner','admin') then raise exception 'Business administrator access required' using errcode='42501'; end if;
 if v_role not in ('admin','staff','customer') then raise exception 'Choose admin, staff or customer role' using errcode='22023'; end if;
 if v_role='admin' and v_actor_role<>'owner' then raise exception 'Only the business owner can add another administrator' using errcode='42501'; end if;
 if v_email !~* '^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$' then raise exception 'Enter a valid email address' using errcode='22023'; end if;
 select s.max_users into v_max_users from public.ps_tenant_subscriptions s
  where s.tenant_id=v_tenant and s.status in ('trial','active')
    and (s.status='active' or s.trial_ends_at is null or s.trial_ends_at>now());
 if v_max_users is null then raise exception 'Workspace subscription is inactive or expired' using errcode='42501'; end if;
 select count(*) into v_used from public.ps_tenant_memberships
  where tenant_id=v_tenant and status in ('active','invited');
 if v_used>=v_max_users then raise exception 'Your plan member limit has been reached' using errcode='42501'; end if;
 select u.id into v_user_id from auth.users u
 join public.ps_profiles p on p.id=u.id
 where lower(u.email)=v_email and p.status='active'
 limit 1;
 if v_user_id is null then raise exception 'No active PaySaathi account exists for this email. Ask the person to create an account first.' using errcode='22023'; end if;
 if exists(select 1 from public.ps_tenant_memberships where tenant_id=v_tenant and user_id=v_user_id) then
  raise exception 'This account is already linked to your workspace' using errcode='23505';
 end if;
 insert into public.ps_tenant_memberships(tenant_id,user_id,role,status,is_default)
 values(v_tenant,v_user_id,v_role,'active',false) returning id into v_membership;
 insert into public.ps_tenant_audit_log(tenant_id,actor_id,action,target_type,target_id,metadata)
 values(v_tenant,auth.uid(),'tenant.member.added','membership',v_membership::text,
  jsonb_build_object('member_user_id',v_user_id,'role',v_role));
 return v_membership;
end;
$$;

revoke all on function public.ps_tenant_list_members() from public,anon;
revoke all on function public.ps_tenant_add_member(text,text) from public,anon;
grant execute on function public.ps_tenant_list_members() to authenticated;
grant execute on function public.ps_tenant_add_member(text,text) to authenticated;

commit;