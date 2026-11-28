-- Enforce plan entitlements server-side; browser controls are not authorization.
create or replace function public.ps_tenant_update_my_branding(p_payload jsonb)
returns jsonb language plpgsql security definer set search_path=public,pg_temp as $$
declare v_tenant uuid; v_brand public.ps_tenant_branding; v_features jsonb;
begin
 v_tenant:=public.ps_current_tenant_id();
 if v_tenant is null or not public.ps_is_tenant_admin(v_tenant) then raise exception 'Tenant administrator access required' using errcode='42501'; end if;
 if jsonb_typeof(coalesce(p_payload,'{}'::jsonb))<>'object' then raise exception 'Branding payload must be an object' using errcode='22023'; end if;
 if p_payload ? 'brand_name' and length(trim(p_payload->>'brand_name')) not between 2 and 80 then raise exception 'Brand name must contain 2 to 80 characters' using errcode='22023'; end if;
 if p_payload ? 'primary_color' and coalesce(p_payload->>'primary_color','') !~ '^#[0-9A-Fa-f]{6}$' then raise exception 'Invalid primary colour' using errcode='22023'; end if;
 if p_payload ? 'accent_color' and coalesce(p_payload->>'accent_color','') !~ '^#[0-9A-Fa-f]{6}$' then raise exception 'Invalid accent colour' using errcode='22023'; end if;
 if p_payload ? 'logo_url' and nullif(trim(p_payload->>'logo_url'),'') is not null and p_payload->>'logo_url' !~ '^https://[^[:space:]]+$' then raise exception 'Logo URL must use HTTPS' using errcode='22023'; end if;
 if p_payload ? 'favicon_url' and nullif(trim(p_payload->>'favicon_url'),'') is not null and p_payload->>'favicon_url' !~ '^https://[^[:space:]]+$' then raise exception 'Favicon URL must use HTTPS' using errcode='22023'; end if;
 if p_payload ? 'hide_platform_branding' and coalesce((p_payload->>'hide_platform_branding')::boolean,false) then
  select features into v_features from public.ps_tenant_subscriptions where tenant_id=v_tenant and status in ('trial','active');
  if coalesce((v_features->>'white_label')::boolean,false) is not true then raise exception 'Your current plan does not include unbranded white-label mode' using errcode='42501'; end if;
 end if;
 insert into public.ps_tenant_branding(tenant_id,brand_name,tagline,logo_url,favicon_url,primary_color,accent_color,support_email,support_phone,support_whatsapp,footer_text,hide_platform_branding,updated_by,updated_at)
 values(v_tenant,coalesce(nullif(trim(p_payload->>'brand_name'),''),'PaySaathi'),nullif(trim(p_payload->>'tagline'),''),nullif(trim(p_payload->>'logo_url'),''),nullif(trim(p_payload->>'favicon_url'),''),
 coalesce(p_payload->>'primary_color','#087fca'),coalesce(p_payload->>'accent_color','#36c5ed'),nullif(trim(p_payload->>'support_email'),''),nullif(trim(p_payload->>'support_phone'),''),nullif(trim(p_payload->>'support_whatsapp'),''),nullif(trim(p_payload->>'footer_text'),''),coalesce((p_payload->>'hide_platform_branding')::boolean,false),auth.uid(),now())
 on conflict(tenant_id) do update set brand_name=excluded.brand_name,tagline=excluded.tagline,logo_url=excluded.logo_url,favicon_url=excluded.favicon_url,primary_color=excluded.primary_color,accent_color=excluded.accent_color,support_email=excluded.support_email,support_phone=excluded.support_phone,support_whatsapp=excluded.support_whatsapp,footer_text=excluded.footer_text,hide_platform_branding=excluded.hide_platform_branding,updated_by=auth.uid(),updated_at=now()
 returning * into v_brand;
 insert into public.ps_tenant_audit_log(tenant_id,actor_id,action,target_type,target_id) values(v_tenant,auth.uid(),'tenant.branding.updated','branding',v_tenant::text);
 return to_jsonb(v_brand);
end; $$;

create or replace function public.ps_platform_create_tenant(p_name text,p_slug text,p_owner_user_id uuid,p_plan_key text default 'starter')
returns uuid language plpgsql security definer set search_path=public,pg_temp as $$
declare v_tenant uuid; v_default boolean; v_features jsonb; v_max_users integer;
begin
 if not public.ps_is_platform_owner() then raise exception 'Platform owner access required' using errcode='42501'; end if;
 if length(trim(coalesce(p_name,''))) not between 2 and 100 then raise exception 'Business name must contain 2 to 100 characters' using errcode='22023'; end if;
 if lower(coalesce(p_slug,'')) !~ '^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$' then raise exception 'Use a 3-50 character lowercase slug with letters, numbers and hyphens' using errcode='22023'; end if;
 if not exists(select 1 from auth.users u join public.ps_profiles p on p.id=u.id where u.id=p_owner_user_id and p.status='active') then raise exception 'Owner must be an existing active PaySaathi account' using errcode='22023'; end if;
 if exists(select 1 from public.ps_tenants where slug=lower(p_slug)) then raise exception 'This tenant URL slug is already in use' using errcode='23505'; end if;
 case lower(coalesce(p_plan_key,'starter'))
  when 'starter' then v_features:='{"white_label":false,"custom_domain":false,"api_access":false}'::jsonb; v_max_users:=25;
  when 'growth' then v_features:='{"white_label":true,"custom_domain":false,"api_access":false}'::jsonb; v_max_users:=100;
  when 'enterprise' then v_features:='{"white_label":true,"custom_domain":true,"api_access":true}'::jsonb; v_max_users:=1000;
  else raise exception 'Unknown subscription plan' using errcode='22023';
 end case;
 insert into public.ps_tenants(slug,name,created_by) values(lower(p_slug),trim(p_name),auth.uid()) returning id into v_tenant;
 select not exists(select 1 from public.ps_tenant_memberships where user_id=p_owner_user_id and is_default and status='active') into v_default;
 insert into public.ps_tenant_memberships(tenant_id,user_id,role,status,is_default) values(v_tenant,p_owner_user_id,'owner','active',v_default);
 insert into public.ps_tenant_branding(tenant_id,brand_name,updated_by) values(v_tenant,trim(p_name),auth.uid());
 insert into public.ps_tenant_subscriptions(tenant_id,plan_key,status,trial_ends_at,max_users,features) values(v_tenant,lower(coalesce(p_plan_key,'starter')),'trial',now()+interval '14 days',v_max_users,v_features);
 insert into public.ps_tenant_audit_log(tenant_id,actor_id,action,target_type,target_id,metadata) values(v_tenant,auth.uid(),'tenant.created','tenant',v_tenant::text,jsonb_build_object('owner_user_id',p_owner_user_id,'plan_key',p_plan_key));
 return v_tenant;
end; $$;

create or replace function public.ps_tenant_add_domain(p_hostname text)
returns jsonb language plpgsql security definer set search_path=public,pg_temp as $$
declare v_tenant uuid; v_host text; v_domain public.ps_tenant_domains; v_features jsonb;
begin
 v_tenant:=public.ps_current_tenant_id();
 if v_tenant is null or not public.ps_is_tenant_admin(v_tenant) then raise exception 'Tenant administrator access required' using errcode='42501'; end if;
 select features into v_features from public.ps_tenant_subscriptions where tenant_id=v_tenant and status in ('trial','active');
 if coalesce((v_features->>'custom_domain')::boolean,false) is not true then raise exception 'Custom domains are not included in your current plan' using errcode='42501'; end if;
 v_host:=lower(trim(coalesce(p_hostname,'')));
 if length(v_host)<4 or length(v_host)>253 or v_host !~ '^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$' or v_host like '%..%' then raise exception 'Enter a valid hostname without protocol, path or port' using errcode='22023'; end if;
 if v_host in ('localhost','paysaathi.com','www.paysaathi.com') then raise exception 'This hostname cannot be registered' using errcode='22023'; end if;
 insert into public.ps_tenant_domains(tenant_id,hostname) values(v_tenant,v_host) on conflict(hostname) do update set hostname=excluded.hostname where public.ps_tenant_domains.tenant_id=excluded.tenant_id returning * into v_domain;
 if v_domain.id is null then raise exception 'This domain is already assigned to another business' using errcode='23505'; end if;
 insert into public.ps_tenant_audit_log(tenant_id,actor_id,action,target_type,target_id) values(v_tenant,auth.uid(),'tenant.domain.added','domain',v_domain.id::text);
 return jsonb_build_object('id',v_domain.id,'hostname',v_domain.hostname,'status',v_domain.status,'verification_token',v_domain.verification_token);
end; $$;