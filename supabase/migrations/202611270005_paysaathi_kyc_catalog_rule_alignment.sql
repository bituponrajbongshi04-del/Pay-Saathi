-- Align KYC rules with the live PaySaathi service catalogue IDs.
insert into public.paysaathi_service_kyc_requirements(service_key,service_label,kyc_required,required_documents)
values
 ('uti','UTI Services',true,array['pan','identity_proof']),
 ('dth','DTH Recharge',false,array[]::text[]),
 ('prepaid','Prepaid Recharge',false,array[]::text[]),
 ('recharge','Mobile Recharge',false,array[]::text[])
on conflict(service_key) do nothing;
