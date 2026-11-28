-- PaySaathi white-label tenant foundation.
-- This is additive: legacy customer, wallet and payment tables are not yet tenant-scoped.
-- Do not onboard commercial tenants until the follow-up data-isolation migration is deployed.
create table if not exists public.ps_tenants (
 id uuid primary key default gen_random_uuid(),
 slug text not null unique check(slug=lower(slug) and slug ~ '^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$'),
 name text not null check(length(trim(name)) between 2 and 100),
 status text not null default 'active' check(status in ('active','suspended','closed')),
 created_by uuid references auth.users(id) on delete set null,
 created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table if not exists public.ps_tenant_memberships (
 id uuid primary key default gen_random_uuid(),
 tenant_id uuid not null references public.ps_tenants(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 role text not null check(role in ('owner','admin','staff','customer')),
 status text not null default 'active' check(status in ('active','suspended','invited')),
 is_default boolean not null default false, created_at timestamptz not null default now(),
 unique(tenant_id,user_id)
);
create unique index if not exists ps_tenant_one_default_membership_per_user on public.ps_tenant_memberships(user_id) where is_default=true;
create index if not exists ps_tenant_memberships_tenant_role_idx on public.ps_tenant_memberships(tenant_id,role,status);
create index if not exists ps_tenant_memberships_user_status_idx on public.ps_tenant_memberships(user_id,status);
create table if not exists public.ps_tenant_branding (
 tenant_id uuid primary key references public.ps_tenants(id) on delete cascade,
 brand_name text not null default 'PaySaathi' check(length(trim(brand_name)) between 2 and 80),
 tagline text, logo_url text, favicon_url text,
 primary_color text not null default '#087fca' check(primary_color ~ '^#[0-9A-Fa-f]{6}$'),
 accent_color text not null default '#36c5ed' check(accent_color ~ '^#[0-9A-Fa-f]{6}$'),
 support_email text, support_phone text, support_whatsapp text, footer_text text,
 hide_platform_branding boolean not null default false,
 updated_by uuid references auth.users(id) on delete set null, updated_at timestamptz not null default now()
);
create table if not exists public.ps_tenant_domains (
 id uuid primary key default gen_random_uuid(),
 tenant_id uuid not null references public.ps_tenants(id) on delete cascade,
 hostname text not null unique check(hostname=lower(hostname) and hostname !~ '[/@:]' and length(hostname) between 4 and 253),
 verification_token text not null default encode(gen_random_bytes(24),'hex'),
 status text not null default 'pending' check(status in ('pending','verified','disabled')),
 verified_at timestamptz, created_at timestamptz not null default now(), unique(tenant_id,hostname)
);
create index if not exists ps_tenant_domains_tenant_status_idx on public.ps_tenant_domains(tenant_id,status);
create table if not exists public.ps_tenant_subscriptions (
 tenant_id uuid primary key references public.ps_tenants(id) on delete cascade,
 plan_key text not null default 'starter',
 status text not null default 'trial' check(status in ('trial','active','past_due','suspended','cancelled')),
 trial_ends_at timestamptz, current_period_start timestamptz, current_period_end timestamptz,
 max_users integer not null default 25 check(max_users between 1 and 100000),
 features jsonb not null default '{}'::jsonb, updated_at timestamptz not null default now()
);
create table if not exists public.ps_tenant_audit_log (
 id bigint generated always as identity primary key,
 tenant_id uuid references public.ps_tenants(id) on delete set null,
 actor_id uuid references auth.users(id) on delete set null,
 action text not null, target_type text, target_id text,
 metadata jsonb not null default '{}'::jsonb, created_at timestamptz not null default now()
);
create index if not exists ps_tenant_audit_log_tenant_created_idx on public.ps_tenant_audit_log(tenant_id,created_at desc);

insert into public.ps_tenants(slug,name,status) values('paysaathi-core','PaySaathi','active') on conflict(slug) do nothing;
insert into public.ps_tenant_branding(tenant_id,brand_name,tagline,primary_color,accent_color)
select id,'PaySaathi','Your money. Your services. One Saathi.','#087fca','#36c5ed' from public.ps_tenants where slug='paysaathi-core' on conflict(tenant_id) do nothing;
insert into public.ps_tenant_subscriptions(tenant_id,plan_key,status,max_users,features)
select id,'legacy','active',1000,'{"white_label":true,"custom_domain":false,"api_access":false}'::jsonb from public.ps_tenants where slug='paysaathi-core' on conflict(tenant_id) do nothing;
insert into public.ps_tenant_memberships(tenant_id,user_id,role,status,is_default)
select t.id,p.id,case when p.role='admin' then 'owner' when p.role='agent' then 'staff' else 'customer' end,case when p.status='active' then 'active' else 'suspended' end,true
from public.ps_profiles p cross join public.ps_tenants t where t.slug='paysaathi-core'
on conflict(tenant_id,user_id) do update set role=excluded.role,status=excluded.status,is_default=true;
insert into public.ps_tenant_memberships(tenant_id,user_id,role,status,is_default)
select t.id,a.user_id,'owner','active',true from public.admin_users a cross join public.ps_tenants t where a.active and t.slug='paysaathi-core'
on conflict(tenant_id,user_id) do update set role='owner',status='active',is_default=true;

create or replace function public.ps_is_platform_owner() returns boolean language sql stable security definer set search_path=public,pg_temp as $$ select coalesce(public.is_paysaathi_admin(),false); $$;
create or replace function public.ps_current_tenant_id() returns uuid language sql stable security definer set search_path=public,pg_temp as $$
 select m.tenant_id from public.ps_tenant_memberships m join public.ps_tenants t on t.id=m.tenant_id and t.status='active'
 where m.user_id=auth.uid() and m.status='active' and m.is_default=true limit 1;
$$;
create or replace function public.ps_is_tenant_admin(p_tenant_id uuid) returns boolean language sql stable security definer set search_path=public,pg_temp as $$
 select exists(select 1 from public.ps_tenant_memberships m join public.ps_tenants t on t.id=m.tenant_id and t.status='active'
 where m.tenant_id=p_tenant_id and m.user_id=auth.uid() and m.status='active' and m.role in ('owner','admin'));
$$;
create or replace function public.ps_tenant_get_my_branding() returns jsonb language sql stable security definer set search_path=public,pg_temp as $$
 select to_jsonb(b) from public.ps_tenant_branding b where b.tenant_id=public.ps_current_tenant_id();
$$;
create or replace function public.ps_tenant_get_public_branding(p_hostname text default null) returns jsonb language sql stable security definer set search_path=public,pg_temp as $$
 select jsonb_build_object('brand_name',b.brand_name,'tagline',b.tagline,'logo_url',b.logo_url,'favicon_url',b.favicon_url,
 'primary_color',b.primary_color,'accent_color',b.accent_color,'support_email',b.support_email,'support_phone',b.support_phone,
 'support_whatsapp',b.support_whatsapp,'footer_text',b.footer_text,'hide_platform_branding',b.hide_platform_branding)
 from public.ps_tenant_domains d join public.ps_tenants t on t.id=d.tenant_id and t.status='active'
 join public.ps_tenant_subscriptions s on s.tenant_id=t.id and s.status in ('trial','active')
 join public.ps_tenant_branding b on b.tenant_id=t.id where d.hostname=lower(trim(coalesce(p_hostname,''))) and d.status='verified' limit 1;
$$;
create or replace function public.ps_tenant_update_my_branding(p_payload jsonb) returns jsonb language plpgsql security definer set search_path=public,pg_temp as $$
declare v_tenant uuid; v_brand public.ps_tenant_branding;
begin
 v_tenant:=public.ps_current_tenant_id();
 if v_tenant is null or not public.ps_is_tenant_admin(v_tenant) then raise exception 'Tenant administrator access required' using errcode='42501'; end if;
 if jsonb_typeof(coalesce(p_payload,'{}'::jsonb))<>'object' then raise exception 'Branding payload must be an object' using errcode='22023'; end if;
 if p_payload ? 'brand_name' and length(trim(p_payload->>'brand_name')) not between 2 and 80 then raise exception 'Brand name must contain 2 to 80 characters' using errcode='22023'; end if;
 if p_payload ? 'primary_color' and coalesce(p_payload->>'primary_color','') !~ '^#[0-9A-Fa-f]{6}$' then raise exception 'Invalid primary colour' using errcode='22023'; end if;
 if p_payload ? 'accent_color' and coalesce(p_payload->>'accent_color','') !~ '^#[0-9A-Fa-f]{6}$' then raise exception 'Invalid accent colour' using errcode='22023'; end if;
 if p_payload ? 'logo_url' and length(coalesce(p_payload->>'logo_url',''))>1000 then raise exception 'Logo URL is too long' using errcode='22023'; end if;
 insert into public.ps_tenant_branding(tenant_id,brand_name,tagline,logo_url,favicon_url,primary_color,accent_color,support_email,support_phone,support_whatsapp,footer_text,hide_platform_branding,updated_by,updated_at)
 values(v_tenant,coalesce(nullif(trim(p_payload->>'brand_name'),''),'PaySaathi'),nullif(trim(p_payload->>'tagline'),''),nullif(trim(p_payload->>'logo_url'),''),nullif(trim(p_payload->>'favicon_url'),''),
 coalesce(p_payload->>'primary_color','#087fca'),coalesce(p_payload->>'accent_color','#36c5ed'),nullif(trim(p_payload->>'support_email'),''),nullif(trim(p_payload->>'support_phone'),''),
 nullif(trim(p_payload->>'support_whatsapp'),''),nullif(trim(p_payload->>'footer_text'),''),coalesce((p_payload->>'hide_platform_branding')::boolean,false),auth.uid(),now())
 on conflict(tenant_id) do update set brand_name=excluded.brand_name,tagline=excluded.tagline,logo_url=excluded.logo_url,favicon_url=excluded.favicon_url,
 primary_color=excluded.primary_color,accent_color=excluded.accent_color,support_email=excluded.support_email,support_phone=excluded.support_phone,
 support_whatsapp=excluded.support_whatsapp,footer_text=excluded.footer_text,hide_platform_branding=excluded.hide_platform_branding,updated_by=auth.uid(),updated_at=now()
 returning * into v_brand;
 insert into public.ps_tenant_audit_log(tenant_id,actor_id,action,target_type,target_id) values(v_tenant,auth.uid(),'tenant.branding.updated','branding',v_tenant::text);
 return to_jsonb(v_brand);
end; $$;
create or replace function public.ps_platform_create_tenant(p_name text,p_slug text,p_owner_user_id uuid,p_plan_key text default 'starter') returns uuid language plpgsql security definer set search_path=public,pg_temp as $$
declare v_tenant uuid; v_default boolean;
begin
 if not public.ps_is_platform_owner() then raise exception 'Platform owner access required' using errcode='42501'; end if;
 if length(trim(coalesce(p_name,''))) not between 2 and 100 then raise exception 'Business name must contain 2 to 100 characters' using errcode='22023'; end if;
 if lower(coalesce(p_slug,'')) !~ '^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$' then raise exception 'Use a 3-50 character lowercase slug with letters, numbers and hyphens' using errcode='22023'; end if;
 if not exists(select 1 from auth.users u join public.ps_profiles p on p.id=u.id where u.id=p_owner_user_id and p.status='active') then raise exception 'Owner must be an existing active PaySaathi account' using errcode='22023'; end if;
 if exists(select 1 from public.ps_tenants where slug=lower(p_slug)) then raise exception 'This tenant URL slug is already in use' using errcode='23505'; end if;
 insert into public.ps_tenants(slug,name,created_by) values(lower(p_slug),trim(p_name),auth.uid()) returning id into v_tenant;
 select not exists(select 1 from public.ps_tenant_memberships where user_id=p_owner_user_id and is_default and status='active') into v_default;
 insert into public.ps_tenant_memberships(tenant_id,user_id,role,status,is_default) values(v_tenant,p_owner_user_id,'owner','active',v_default);
 insert into public.ps_tenant_branding(tenant_id,brand_name,updated_by) values(v_tenant,trim(p_name),auth.uid());
 insert into public.ps_tenant_subscriptions(tenant_id,plan_key,status,trial_ends_at,max_users) values(v_tenant,coalesce(nullif(trim(p_plan_key),''),'starter'),'trial',now()+interval '14 days',25);
 insert into public.ps_tenant_audit_log(tenant_id,actor_id,action,target_type,target_id,metadata) values(v_tenant,auth.uid(),'tenant.created','tenant',v_tenant::text,jsonb_build_object('owner_user_id',p_owner_user_id,'plan_key',p_plan_key));
 return v_tenant;
end; $$;
create or replace function public.ps_tenant_list_available() returns jsonb language sql stable security definer set search_path=public,pg_temp as $$
 select coalesce(jsonb_agg(jsonb_build_object('id',t.id,'slug',t.slug,'name',t.name,'status',t.status,'role',m.role,'is_default',m.is_default,'plan_key',s.plan_key,'subscription_status',s.status,'trial_ends_at',s.trial_ends_at) order by m.is_default desc,t.created_at asc),'[]'::jsonb)
 from public.ps_tenant_memberships m join public.ps_tenants t on t.id=m.tenant_id left join public.ps_tenant_subscriptions s on s.tenant_id=t.id
 where m.user_id=auth.uid() and m.status='active' and t.status='active';
$$;
create or replace function public.ps_tenant_set_active(p_tenant_id uuid) returns boolean language plpgsql security definer set search_path=public,pg_temp as $$
begin
 if not exists(select 1 from public.ps_tenant_memberships m join public.ps_tenants t on t.id=m.tenant_id and t.status='active'
 where m.tenant_id=p_tenant_id and m.user_id=auth.uid() and m.status='active') then raise exception 'You are not an active member of this business' using errcode='42501'; end if;
 update public.ps_tenant_memberships set is_default=false where user_id=auth.uid() and is_default=true;
 update public.ps_tenant_memberships set is_default=true where user_id=auth.uid() and tenant_id=p_tenant_id and status='active';
 return true;
end; $$;
create or replace function public.ps_tenant_add_domain(p_hostname text) returns jsonb language plpgsql security definer set search_path=public,pg_temp as $$
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

alter table public.ps_tenants enable row level security;
alter table public.ps_tenant_memberships enable row level security;
alter table public.ps_tenant_branding enable row level security;
alter table public.ps_tenant_domains enable row level security;
alter table public.ps_tenant_subscriptions enable row level security;
alter table public.ps_tenant_audit_log enable row level security;
drop policy if exists ps_tenants_member_read on public.ps_tenants;
create policy ps_tenants_member_read on public.ps_tenants for select to authenticated using(public.ps_is_platform_owner() or exists(select 1 from public.ps_tenant_memberships m where m.tenant_id=id and m.user_id=auth.uid() and m.status='active'));
drop policy if exists ps_tenant_memberships_read on public.ps_tenant_memberships;
create policy ps_tenant_memberships_read on public.ps_tenant_memberships for select to authenticated using(user_id=auth.uid() or public.ps_is_platform_owner() or public.ps_is_tenant_admin(tenant_id));
drop policy if exists ps_tenant_branding_member_read on public.ps_tenant_branding;
create policy ps_tenant_branding_member_read on public.ps_tenant_branding for select to authenticated using(tenant_id=public.ps_current_tenant_id() or public.ps_is_platform_owner());
drop policy if exists ps_tenant_domains_admin_read on public.ps_tenant_domains;
create policy ps_tenant_domains_admin_read on public.ps_tenant_domains for select to authenticated using(public.ps_is_platform_owner() or public.ps_is_tenant_admin(tenant_id));
drop policy if exists ps_tenant_subscriptions_admin_read on public.ps_tenant_subscriptions;
create policy ps_tenant_subscriptions_admin_read on public.ps_tenant_subscriptions for select to authenticated using(public.ps_is_platform_owner() or public.ps_is_tenant_admin(tenant_id));
drop policy if exists ps_tenant_audit_admin_read on public.ps_tenant_audit_log;
create policy ps_tenant_audit_admin_read on public.ps_tenant_audit_log for select to authenticated using(public.ps_is_platform_owner() or (tenant_id=public.ps_current_tenant_id() and public.ps_is_tenant_admin(tenant_id)));
revoke all on public.ps_tenants,public.ps_tenant_memberships,public.ps_tenant_branding,public.ps_tenant_domains,public.ps_tenant_subscriptions,public.ps_tenant_audit_log from anon,authenticated;
grant select on public.ps_tenants,public.ps_tenant_memberships,public.ps_tenant_branding,public.ps_tenant_domains,public.ps_tenant_subscriptions,public.ps_tenant_audit_log to authenticated;
revoke all on function public.ps_is_platform_owner(),public.ps_current_tenant_id(),public.ps_tenant_get_my_branding(),public.ps_tenant_list_available() from public,anon;
revoke all on function public.ps_is_tenant_admin(uuid),public.ps_tenant_get_public_branding(text),public.ps_tenant_update_my_branding(jsonb),public.ps_platform_create_tenant(text,text,uuid,text),public.ps_tenant_set_active(uuid),public.ps_tenant_add_domain(text) from public,anon;
grant execute on function public.ps_is_platform_owner(),public.ps_current_tenant_id(),public.ps_is_tenant_admin(uuid),public.ps_tenant_get_my_branding(),public.ps_tenant_update_my_branding(jsonb),public.ps_platform_create_tenant(text,text,uuid,text),public.ps_tenant_list_available(),public.ps_tenant_set_active(uuid),public.ps_tenant_add_domain(text) to authenticated;
grant execute on function public.ps_tenant_get_public_branding(text) to anon,authenticated;
