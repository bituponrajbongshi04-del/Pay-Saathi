-- Tenant management RPCs. All entry points derive identity from auth.uid().
create or replace function public.ps_tenant_list_available()
returns jsonb language sql stable security definer set search_path=public,pg_temp as $$
 select coalesce(jsonb_agg(jsonb_build_object('id',t.id,'slug',t.slug,'name',t.name,'status',t.status,'role',m.role,'is_default',m.is_default,'plan_key',s.plan_key,'subscription_status',s.status,'trial_ends_at',s.trial_ends_at) order by m.is_default desc,t.created_at asc),'[]'::jsonb)
 from public.ps_tenant_memberships m join public.ps_tenants t on t.id=m.tenant_id left join public.ps_tenant_subscriptions s on s.tenant_id=t.id
 where m.user_id=auth.uid() and m.status='active' and t.status='active';
$$;
create or replace function public.ps_tenant_set_active(p_tenant_id uuid)
returns boolean language plpgsql security definer set search_path=public,pg_temp as $$
begin
 if not exists(select 1 from public.ps_tenant_memberships m join public.ps_tenants t on t.id=m.tenant_id and t.status='active'
 where m.tenant_id=p_tenant_id and m.user_id=auth.uid() and m.status='active') then raise exception 'You are not an active member of this business' using errcode='42501'; end if;
 update public.ps_tenant_memberships set is_default=false where user_id=auth.uid() and is_default=true;
 update public.ps_tenant_memberships set is_default=true where user_id=auth.uid() and tenant_id=p_tenant_id and status='active';
 return true;
end; $$;
create or replace function public.ps_tenant_add_domain(p_hostname text)
returns jsonb language plpgsql security definer set search_path=public,pg_temp as $$
declare v_tenant uuid; v_host text; v_domain public.ps_tenant_domains;
begin
 v_tenant:=public.ps_current_tenant_id();
 if v_tenant is null or not public.ps_is_tenant_admin(v_tenant) then raise exception 'Tenant administrator access required' using errcode='42501'; end if;
 v_host:=lower(trim(coalesce(p_hostname,'')));
 if length(v_host)<4 or length(v_host)>253 or v_host !~ '^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$' or v_host like '%..%' then raise exception 'Enter a valid hostname without protocol, path or port' using errcode='22023'; end if;
 if v_host in ('localhost','paysaathi.com','www.paysaathi.com') then raise exception 'This hostname cannot be registered' using errcode='22023'; end if;
 insert into public.ps_tenant_domains(tenant_id,hostname) values(v_tenant,v_host)
 on conflict(hostname) do update set hostname=excluded.hostname where public.ps_tenant_domains.tenant_id=excluded.tenant_id returning * into v_domain;
 if v_domain.id is null then raise exception 'This domain is already assigned to another business' using errcode='23505'; end if;
 insert into public.ps_tenant_audit_log(tenant_id,actor_id,action,target_type,target_id) values(v_tenant,auth.uid(),'tenant.domain.added','domain',v_domain.id::text);
 return jsonb_build_object('id',v_domain.id,'hostname',v_domain.hostname,'status',v_domain.status,'verification_token',v_domain.verification_token);
end; $$;
revoke all on function public.ps_tenant_list_available(),public.ps_tenant_set_active(uuid),public.ps_tenant_add_domain(text) from public,anon;
grant execute on function public.ps_tenant_list_available(),public.ps_tenant_set_active(uuid),public.ps_tenant_add_domain(text) to authenticated;