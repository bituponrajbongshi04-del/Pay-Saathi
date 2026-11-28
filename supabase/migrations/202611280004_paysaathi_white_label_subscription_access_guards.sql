-- Enforce active subscription/trial status for tenant context, workspace switching and public brand resolution.
create or replace function public.ps_current_tenant_id()
returns uuid language sql stable security definer set search_path=public,pg_temp as $$
 select m.tenant_id from public.ps_tenant_memberships m
 join public.ps_tenants t on t.id=m.tenant_id and t.status='active'
 join public.ps_tenant_subscriptions s on s.tenant_id=t.id and s.status in ('trial','active')
 where m.user_id=auth.uid() and m.status='active' and m.is_default=true
 and (s.status='active' or s.trial_ends_at is null or s.trial_ends_at>now()) limit 1;
$$;
create or replace function public.ps_tenant_list_available()
returns jsonb language sql stable security definer set search_path=public,pg_temp as $$
 select coalesce(jsonb_agg(jsonb_build_object('id',t.id,'slug',t.slug,'name',t.name,'status',t.status,'role',m.role,'is_default',m.is_default,'plan_key',s.plan_key,'subscription_status',s.status,'trial_ends_at',s.trial_ends_at) order by m.is_default desc,t.created_at asc),'[]'::jsonb)
 from public.ps_tenant_memberships m join public.ps_tenants t on t.id=m.tenant_id and t.status='active'
 join public.ps_tenant_subscriptions s on s.tenant_id=t.id and s.status in ('trial','active')
 where m.user_id=auth.uid() and m.status='active' and (s.status='active' or s.trial_ends_at is null or s.trial_ends_at>now());
$$;
create or replace function public.ps_tenant_set_active(p_tenant_id uuid)
returns boolean language plpgsql security definer set search_path=public,pg_temp as $$
begin
 if not exists(select 1 from public.ps_tenant_memberships m join public.ps_tenants t on t.id=m.tenant_id and t.status='active'
 join public.ps_tenant_subscriptions s on s.tenant_id=t.id and s.status in ('trial','active')
 where m.tenant_id=p_tenant_id and m.user_id=auth.uid() and m.status='active' and (s.status='active' or s.trial_ends_at is null or s.trial_ends_at>now()))
 then raise exception 'This business is unavailable or its subscription has expired' using errcode='42501'; end if;
 update public.ps_tenant_memberships set is_default=false where user_id=auth.uid() and is_default=true;
 update public.ps_tenant_memberships set is_default=true where user_id=auth.uid() and tenant_id=p_tenant_id and status='active';
 return true;
end; $$;
create or replace function public.ps_tenant_get_public_branding(p_hostname text default null)
returns jsonb language sql stable security definer set search_path=public,pg_temp as $$
 select jsonb_build_object('brand_name',b.brand_name,'tagline',b.tagline,'logo_url',b.logo_url,'favicon_url',b.favicon_url,'primary_color',b.primary_color,'accent_color',b.accent_color,'support_email',b.support_email,'support_phone',b.support_phone,'support_whatsapp',b.support_whatsapp,'footer_text',b.footer_text,'hide_platform_branding',b.hide_platform_branding)
 from public.ps_tenant_domains d join public.ps_tenants t on t.id=d.tenant_id and t.status='active'
 join public.ps_tenant_subscriptions s on s.tenant_id=t.id and s.status in ('trial','active')
 join public.ps_tenant_branding b on b.tenant_id=t.id where d.hostname=lower(trim(coalesce(p_hostname,''))) and d.status='verified'
 and (s.status='active' or s.trial_ends_at is null or s.trial_ends_at>now()) limit 1;
$$;