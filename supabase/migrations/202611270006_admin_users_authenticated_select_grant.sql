-- Allow signed-in clients to query their own admin membership row.
-- The existing RLS policy limits SELECT to user_id = auth.uid(); anon remains denied.
grant select on table public.admin_users to authenticated;
revoke all on table public.admin_users from anon;
