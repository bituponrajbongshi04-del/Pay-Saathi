-- Explicit least-privilege grants for the KYC REST endpoints and function.
grant select,insert,update on public.paysaathi_kyc_profiles to authenticated;
grant select,insert,update on public.paysaathi_kyc_documents to authenticated;
grant select,update on public.paysaathi_service_kyc_requirements to authenticated;
revoke all on function public.paysaathi_kyc_access(text) from public;
revoke all on function public.paysaathi_kyc_access(text) from anon;
grant execute on function public.paysaathi_kyc_access(text) to authenticated;
