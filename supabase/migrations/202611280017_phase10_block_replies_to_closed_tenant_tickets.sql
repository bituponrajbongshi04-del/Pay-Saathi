-- Phase 10: prevent replies to closed tenant support tickets.
begin;
create or replace function public.ps_tenant_staff_reply_ticket(p_ticket_id uuid,p_message text)
returns uuid
language plpgsql
security definer
set search_path=public,pg_temp
as $$
declare v_tenant uuid; v_role text; v_assigned uuid; v_status text; v_id uuid;
begin
 v_tenant:=public.ps_current_tenant_id();
 if v_tenant is null then raise exception 'No active business workspace' using errcode='42501'; end if;
 select role into v_role from public.ps_tenant_memberships
 where tenant_id=v_tenant and user_id=auth.uid() and status='active';
 if v_role not in ('owner','admin','staff') then raise exception 'Business support staff access required' using errcode='42501'; end if;
 if p_message is null or char_length(trim(p_message))<1 or char_length(trim(p_message))>2000 then
  raise exception 'Reply must contain 1 to 2000 characters' using errcode='22023';
 end if;
 select assigned_agent,status into v_assigned,v_status from public.ps_tickets
 where id=p_ticket_id and tenant_id=v_tenant;
 if not found then raise exception 'Ticket not found in this workspace' using errcode='P0002'; end if;
 if lower(coalesce(v_status,''))='closed' then raise exception 'This ticket is closed. Reopen it before replying.' using errcode='22023'; end if;
 if v_role='staff' and v_assigned is distinct from auth.uid() then
  raise exception 'You can reply only to tickets assigned to you' using errcode='42501';
 end if;
 insert into public.ps_ticket_messages(ticket_id,sender_id,message,tenant_id)
 values(p_ticket_id,auth.uid(),trim(p_message),v_tenant) returning id into v_id;
 return v_id;
end;
$$;
revoke all on function public.ps_tenant_staff_reply_ticket(uuid,text) from public,anon;
grant execute on function public.ps_tenant_staff_reply_ticket(uuid,text) to authenticated;
commit;