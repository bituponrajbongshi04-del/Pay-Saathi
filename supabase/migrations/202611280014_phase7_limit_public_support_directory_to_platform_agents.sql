-- Phase 7: expose only the platform's active support-agent directory publicly.
-- Tenant staff must not be discoverable through the platform-wide directory RPC.
begin;
create or replace function public.paysaathi_get_public_support_agents()
returns table(user_id uuid,full_name text,details text,availability text,presence_status text,status text)
language sql
stable
security definer
set search_path=public,pg_temp
as $$
 select s.user_id,s.full_name,
        null::text as details,
        null::text as availability,
        null::text as presence_status,
        'active'::text as status
 from public.paysaathi_support_staff s
 join public.ps_profiles p on p.id=s.user_id
 where s.status='active'
   and p.status='active'
   and p.role='agent'
 order by s.created_at asc;
$$;
revoke all on function public.paysaathi_get_public_support_agents() from public;
grant execute on function public.paysaathi_get_public_support_agents() to anon,authenticated;
commit;