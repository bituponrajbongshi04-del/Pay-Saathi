-- These configuration/catalog tables are accessed through vetted RPCs.
-- RLS without policies already denies row access; remove broad table grants too.
revoke all on table public.paysaathi_gateway_config from anon, authenticated;
revoke all on table public.paysaathi_service_catalog from anon, authenticated;
revoke all on table public.paysaathi_branding_config from anon, authenticated;
revoke all on table public.paysaathi_password_otp_challenges from anon, authenticated;
revoke all on table public.paysaathi_site_settings from anon, authenticated;
revoke all on table public.paysaathi_test_payment_orders from anon, authenticated;
revoke all on table public.ps_login_otp_challenges from anon, authenticated;
revoke all on table public.ps_wallet_adjustment_audit from anon, authenticated;
revoke all on table public.ps_wallet_controls from anon, authenticated;
revoke all on table public.ps_wallet_security_audit from anon, authenticated;

-- Ensure future tables created by the API-facing owner do not accidentally
-- inherit broad client privileges for these private operational schemas.
