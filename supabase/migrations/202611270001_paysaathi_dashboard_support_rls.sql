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

-- Short-lived password + email OTP challenges. Plaintext passwords and OTPs are never stored.
create table if not exists public.ps_login_otp_challenges (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  email text not null,
  code_hash text not null,
  attempts integer not null default 0 check (attempts between 0 and 5),
  expires_at timestamptz not null,
  consumed_at timestamptz,
  created_at timestamptz not null default now()
);
create index if not exists ps_login_otp_email_created_idx
  on public.ps_login_otp_challenges(email, created_at desc);
alter table public.ps_login_otp_challenges enable row level security;
revoke all on public.ps_login_otp_challenges from anon, authenticated;
grant all on public.ps_login_otp_challenges to service_role;

-- Public read for branding assets; only verified admins may upload or change them.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('paysaathi-branding','paysaathi-branding',true,3145728,array['image/png','image/jpeg','image/webp','image/svg+xml'])
on conflict (id) do update set public=true, file_size_limit=3145728,
  allowed_mime_types=array['image/png','image/jpeg','image/webp','image/svg+xml'];
drop policy if exists paysaathi_branding_public_read on storage.objects;
create policy paysaathi_branding_public_read on storage.objects
for select to public using (bucket_id='paysaathi-branding');
drop policy if exists paysaathi_branding_admin_insert on storage.objects;
create policy paysaathi_branding_admin_insert on storage.objects
for insert to authenticated with check (bucket_id='paysaathi-branding' and public.is_paysaathi_admin());
drop policy if exists paysaathi_branding_admin_update on storage.objects;
create policy paysaathi_branding_admin_update on storage.objects
for update to authenticated using (bucket_id='paysaathi-branding' and public.is_paysaathi_admin())
with check (bucket_id='paysaathi-branding' and public.is_paysaathi_admin());
drop policy if exists paysaathi_branding_admin_delete on storage.objects;
create policy paysaathi_branding_admin_delete on storage.objects
for delete to authenticated using (bucket_id='paysaathi-branding' and public.is_paysaathi_admin());

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
