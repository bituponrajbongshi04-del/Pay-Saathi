-- PaySaathi dashboard/support integration migration
-- Additive migration based on the project's supplied migration file.
-- Review table/function prerequisites in the target Supabase project before applying.
-- This does not connect a live recharge/payment provider.

create extension if not exists pgcrypto;

-- Backfill profiles and zero-balance wallets for accounts that existed before the trigger.
insert into public.ps_profiles (id, full_name, role, status)
select u.id,
       coalesce(u.raw_user_meta_data->>'full_name', u.raw_user_meta_data->>'name', ''),
       case when lower(coalesce(u.email,'')) = lower('bituponrajbongshi04@gmail.com') then 'admin' else 'customer' end,
       'active'
from auth.users u
on conflict (id) do update set
  role = case when lower(coalesce((select email from auth.users where id=excluded.id),'')) = lower('bituponrajbongshi04@gmail.com') then 'admin' else public.ps_profiles.role end;

insert into public.ps_wallets(user_id)
select id from auth.users
on conflict (user_id) do nothing;

-- Ensure the designated owner is admin.
update public.ps_profiles p set role='admin', status='active'
from auth.users u
where p.id=u.id and lower(u.email)=lower('bituponrajbongshi04@gmail.com');

-- SECURITY FIX: users must not be able to change their own role/status through profile updates.
drop policy if exists ps_profile_update on public.ps_profiles;
drop policy if exists ps_profile_admin_update on public.ps_profiles;
create policy ps_profile_admin_update on public.ps_profiles
for update to authenticated
using (public.ps_is_admin())
with check (public.ps_is_admin());

-- Helper for assigned support agents.
create or replace function public.ps_is_agent()
returns boolean language sql security definer set search_path=public stable as $$
  select exists(select 1 from public.ps_profiles where id=auth.uid() and role='agent' and status='active');
$$;

-- Ticket access: owner, admin, or assigned agent.
drop policy if exists ps_ticket_read on public.ps_tickets;
create policy ps_ticket_read on public.ps_tickets
for select to authenticated
using (user_id=auth.uid() or assigned_agent=auth.uid() or public.ps_is_admin());

drop policy if exists ps_ticket_agent_update on public.ps_tickets;
create policy ps_ticket_agent_update on public.ps_tickets
for update to authenticated
using (assigned_agent=auth.uid() and public.ps_is_agent())
with check (assigned_agent=auth.uid() and public.ps_is_agent());

-- Ensure customer ticket creation remains owner-only.
drop policy if exists ps_ticket_customer_insert on public.ps_tickets;
create policy ps_ticket_customer_insert on public.ps_tickets
for insert to authenticated
with check (user_id=auth.uid() and assigned_agent is null);

-- Reassert message access for ticket participants.
drop policy if exists ps_message_read on public.ps_ticket_messages;
create policy ps_message_read on public.ps_ticket_messages
for select to authenticated
using (exists(select 1 from public.ps_tickets t where t.id=ticket_id and
 (t.user_id=auth.uid() or t.assigned_agent=auth.uid() or public.ps_is_admin())));

drop policy if exists ps_message_insert on public.ps_ticket_messages;
create policy ps_message_insert on public.ps_ticket_messages
for insert to authenticated
with check (sender_id=auth.uid() and exists(select 1 from public.ps_tickets t where t.id=ticket_id and
 (t.user_id=auth.uid() or t.assigned_agent=auth.uid() or public.ps_is_admin())));

-- Personal and global notifications. A null user_id represents a site-wide message.
create table if not exists public.ps_notifications (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users(id) on delete cascade,
  title text not null,
  message text not null,
  is_read boolean not null default false,
  created_at timestamptz not null default now()
);
create index if not exists ps_notifications_user_created_idx
  on public.ps_notifications(user_id, created_at desc);
alter table public.ps_notifications enable row level security;
grant select, insert, update on public.ps_notifications to authenticated;

-- Personal/global notification read and admin-only send.
drop policy if exists ps_notification_read on public.ps_notifications;
create policy ps_notification_read on public.ps_notifications
for select to authenticated using (user_id=auth.uid() or user_id is null or public.ps_is_admin());
drop policy if exists ps_notification_update on public.ps_notifications;
create policy ps_notification_update on public.ps_notifications
for update to authenticated using (user_id=auth.uid() or public.ps_is_admin())
with check (user_id=auth.uid() or public.ps_is_admin());
drop policy if exists ps_notification_admin on public.ps_notifications;
create policy ps_notification_admin on public.ps_notifications
for insert to authenticated with check (public.ps_is_admin());

-- Wallet and transaction rows are read-only to browser clients; never credit balances from HTML.
