-- Phase 9: return tenant-scoped support conversation history with each ticket.
begin;
create or replace function public.ps_tenant_staff_list_tickets()
returns jsonb
language plpgsql
stable
security definer
set search_path=public,pg_temp
as $$
declare v_tenant uuid; v_role text; v_result jsonb;
begin
 v_tenant:=public.ps_current_tenant_id();
 if v_tenant is null then raise exception 'No active business workspace' using errcode='42501'; end if;
 select role into v_role from public.ps_tenant_memberships
 where tenant_id=v_tenant and user_id=auth.uid() and status='active';
 if v_role not in ('owner','admin','staff') then
  raise exception 'Business support staff access required' using errcode='42501';
 end if;
 select coalesce(jsonb_agg(jsonb_build_object(
  'id',t.id,'user_id',t.user_id,'customer_name',coalesce(nullif(p.full_name,''),'Customer'),
  'subject',t.subject,'message',t.message,'status',t.status,
  'assigned_agent',t.assigned_agent,'created_at',t.created_at,
  'messages',(
   select coalesce(jsonb_agg(jsonb_build_object(
    'sender_id',m.sender_id,
    'sender_name',coalesce(nullif(mp.full_name,''),case when m.sender_id=t.user_id then 'Customer' else 'Support' end),
    'message',m.message,'created_at',m.created_at
   ) order by m.created_at),'[]'::jsonb)
   from public.ps_ticket_messages m
   left join public.ps_profiles mp on mp.id=m.sender_id
   where m.ticket_id=t.id and m.tenant_id=v_tenant
  )
 ) order by t.created_at desc),'[]'::jsonb)
 into v_result
 from public.ps_tickets t
 left join public.ps_profiles p on p.id=t.user_id
 where t.tenant_id=v_tenant
   and (v_role in ('owner','admin') or t.assigned_agent=auth.uid());
 return v_result;
end;
$$;
revoke all on function public.ps_tenant_staff_list_tickets() from public,anon;
grant execute on function public.ps_tenant_staff_list_tickets() to authenticated;
commit;