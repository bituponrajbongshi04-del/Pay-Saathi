-- Phase 8: tenant-scoped support desk for workspace staff and owners.
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
  'assigned_agent',t.assigned_agent,'created_at',t.created_at
 ) order by t.created_at desc),'[]'::jsonb)
 into v_result
 from public.ps_tickets t
 left join public.ps_profiles p on p.id=t.user_id
 where t.tenant_id=v_tenant
   and (v_role in ('owner','admin') or t.assigned_agent=auth.uid());
 return v_result;
end;
$$;

create or replace function public.ps_tenant_staff_reply_ticket(p_ticket_id uuid,p_message text)
returns uuid
language plpgsql
security definer
set search_path=public,pg_temp
as $$
declare v_tenant uuid; v_role text; v_assigned uuid; v_id uuid;
begin
 v_tenant:=public.ps_current_tenant_id();
 if v_tenant is null then raise exception 'No active business workspace' using errcode='42501'; end if;
 select role into v_role from public.ps_tenant_memberships
 where tenant_id=v_tenant and user_id=auth.uid() and status='active';
 if v_role not in ('owner','admin','staff') then raise exception 'Business support staff access required' using errcode='42501'; end if;
 if p_message is null or char_length(trim(p_message))<1 or char_length(trim(p_message))>2000 then
  raise exception 'Reply must contain 1 to 2000 characters' using errcode='22023';
 end if;
 select assigned_agent into v_assigned from public.ps_tickets
 where id=p_ticket_id and tenant_id=v_tenant;
 if not found then raise exception 'Ticket not found in this workspace' using errcode='P0002'; end if;
 if v_role='staff' and v_assigned is distinct from auth.uid() then
  raise exception 'You can reply only to tickets assigned to you' using errcode='42501';
 end if;
 insert into public.ps_ticket_messages(ticket_id,sender_id,message,tenant_id)
 values(p_ticket_id,auth.uid(),trim(p_message),v_tenant) returning id into v_id;
 return v_id;
end;
$$;

create or replace function public.ps_tenant_staff_update_ticket_status(p_ticket_id uuid,p_status text)
returns boolean
language plpgsql
security definer
set search_path=public,pg_temp
as $$
declare v_tenant uuid; v_role text; v_assigned uuid; v_status text:=lower(trim(coalesce(p_status,'')));
begin
 v_tenant:=public.ps_current_tenant_id();
 if v_tenant is null then raise exception 'No active business workspace' using errcode='42501'; end if;
 select role into v_role from public.ps_tenant_memberships
 where tenant_id=v_tenant and user_id=auth.uid() and status='active';
 if v_role not in ('owner','admin','staff') then raise exception 'Business support staff access required' using errcode='42501'; end if;
 if v_status not in ('open','in_progress','closed') then raise exception 'Invalid ticket status' using errcode='22023'; end if;
 select assigned_agent into v_assigned from public.ps_tickets
 where id=p_ticket_id and tenant_id=v_tenant for update;
 if not found then raise exception 'Ticket not found in this workspace' using errcode='P0002'; end if;
 if v_role='staff' and v_assigned is distinct from auth.uid() then
  raise exception 'You can update only tickets assigned to you' using errcode='42501';
 end if;
 update public.ps_tickets set status=v_status where id=p_ticket_id and tenant_id=v_tenant;
 return true;
end;
$$;

create or replace function public.ps_tenant_admin_assign_ticket(p_ticket_id uuid,p_staff_user_id uuid)
returns boolean
language plpgsql
security definer
set search_path=public,pg_temp
as $$
declare v_tenant uuid; v_role text;
begin
 v_tenant:=public.ps_current_tenant_id();
 if v_tenant is null then raise exception 'No active business workspace' using errcode='42501'; end if;
 select role into v_role from public.ps_tenant_memberships
 where tenant_id=v_tenant and user_id=auth.uid() and status='active';
 if v_role not in ('owner','admin') then raise exception 'Only workspace owners and administrators can assign tickets' using errcode='42501'; end if;
 if not exists(select 1 from public.ps_tenant_memberships
   where tenant_id=v_tenant and user_id=p_staff_user_id and role='staff' and status='active') then
  raise exception 'Select an active staff member from this workspace' using errcode='22023';
 end if;
 update public.ps_tickets set assigned_agent=p_staff_user_id
 where id=p_ticket_id and tenant_id=v_tenant;
 if not found then raise exception 'Ticket not found in this workspace' using errcode='P0002'; end if;
 return true;
end;
$$;

revoke all on function public.ps_tenant_staff_list_tickets() from public,anon;
revoke all on function public.ps_tenant_staff_reply_ticket(uuid,text) from public,anon;
revoke all on function public.ps_tenant_staff_update_ticket_status(uuid,text) from public,anon;
revoke all on function public.ps_tenant_admin_assign_ticket(uuid,uuid) from public,anon;
grant execute on function public.ps_tenant_staff_list_tickets() to authenticated;
grant execute on function public.ps_tenant_staff_reply_ticket(uuid,text) to authenticated;
grant execute on function public.ps_tenant_staff_update_ticket_status(uuid,text) to authenticated;
grant execute on function public.ps_tenant_admin_assign_ticket(uuid,uuid) to authenticated;

commit;