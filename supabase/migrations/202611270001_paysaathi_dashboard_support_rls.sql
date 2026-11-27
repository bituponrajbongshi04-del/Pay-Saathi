-- PaySaathi production-compatible additive integration migration.
-- Reconciled against the existing PaySaathi Supabase schema on 2026-11-27.
-- Preserves existing branding, service, support, wallet and notification data.

create extension if not exists pgcrypto;

-- Prevent customers from changing their own role/status through direct profile updates.
-- Profile edits are handled by the existing SECURITY DEFINER profile-sync RPC.
alter table public.ps_profiles enable row level security;
drop policy if exists ps_profile_update on public.ps_profiles;
drop policy if exists ps_profile_admin_update on public.ps_profiles;
create policy ps_profile_admin_update on public.ps_profiles
for update to authenticated
using (public.ps_is_admin())
with check (public.ps_is_admin());

-- Six-digit password + email OTP challenges. Only the Edge Function service role can access them.
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
create index if not exists ps_login_otp_email_created_idx on public.ps_login_otp_challenges(email, created_at desc);
alter table public.ps_login_otp_challenges enable row level security;
revoke all on public.ps_login_otp_challenges from public, anon, authenticated;
grant all on public.ps_login_otp_challenges to service_role;

-- Retain existing branding bucket/read/upload/update policies; add admin-only delete permission.
drop policy if exists paysaathi_branding_admin_delete on storage.objects;
create policy paysaathi_branding_admin_delete on storage.objects
for delete to authenticated
using (bucket_id='paysaathi-branding' and public.is_paysaathi_admin());

-- Keep existing personal/global notification schema and policies.
grant select, insert, update on public.ps_notifications to authenticated;
