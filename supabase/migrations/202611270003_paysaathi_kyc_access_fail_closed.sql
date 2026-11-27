-- Fail closed for services without a KYC rule; explicitly mark common basic services as non-KYC.
insert into public.paysaathi_service_kyc_requirements(service_key,service_label,kyc_required,required_documents)
values
 ('mobile_recharge','Mobile Recharge',false,array[]::text[]),
 ('dth_recharge','DTH Recharge',false,array[]::text[]),
 ('electricity','Electricity Bills',false,array[]::text[]),
 ('gas','Gas Bills',false,array[]::text[]),
 ('water','Water Bills',false,array[]::text[]),
 ('broadband','Broadband',false,array[]::text[]),
 ('postpaid','Postpaid Bills',false,array[]::text[])
on conflict(service_key) do nothing;
create or replace function public.paysaathi_kyc_access(p_service_key text) returns jsonb
language plpgsql security definer set search_path=public,auth as $$
declare v_uid uuid:=auth.uid(); v_profile public.paysaathi_kyc_profiles%rowtype; v_rule public.paysaathi_service_kyc_requirements%rowtype; v_missing text[];
begin
 if v_uid is null then return jsonb_build_object('allowed',false,'reason','login_required'); end if;
 select * into v_rule from public.paysaathi_service_kyc_requirements where service_key=p_service_key and active=true;
 if not found then return jsonb_build_object('allowed',false,'reason','service_rule_missing'); end if;
 if not v_rule.kyc_required then return jsonb_build_object('allowed',true,'status','not_required'); end if;
 select * into v_profile from public.paysaathi_kyc_profiles where user_id=v_uid;
 if not found or v_profile.status<>'approved' then return jsonb_build_object('allowed',false,'status',coalesce(v_profile.status,'not_started'),'reason','kyc_required','service_label',v_rule.service_label); end if;
 select array_agg(req) into v_missing from unnest(v_rule.required_documents) req where not exists(select 1 from public.paysaathi_kyc_documents d where d.user_id=v_uid and d.document_type=req and d.status='approved');
 if coalesce(array_length(v_missing,1),0)>0 then return jsonb_build_object('allowed',false,'status',v_profile.status,'reason','documents_missing','missing_documents',v_missing); end if;
 return jsonb_build_object('allowed',true,'status','approved');
end $$;
revoke all on function public.paysaathi_kyc_access(text) from public;
grant execute on function public.paysaathi_kyc_access(text) to authenticated;
