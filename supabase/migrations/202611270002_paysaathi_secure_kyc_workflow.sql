-- Secure PaySaathi KYC: private documents, review workflow and server-side service gate.
create table if not exists public.paysaathi_kyc_profiles (
 user_id uuid primary key references auth.users(id) on delete cascade,
 status text not null default 'not_started' check(status in ('not_started','draft','submitted','under_review','approved','rejected')),
 legal_name text, submitted_at timestamptz, reviewed_at timestamptz, reviewed_by uuid references auth.users(id),
 rejection_reason text, provider text not null default 'manual_review', provider_reference text,
 created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table if not exists public.paysaathi_kyc_documents (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references auth.users(id) on delete cascade,
 document_type text not null check(document_type in ('pan','identity_proof','address_proof')),
 storage_path text not null unique, original_file_name text,
 mime_type text not null check(mime_type in ('image/jpeg','image/png','application/pdf')),
 file_size_bytes bigint not null check(file_size_bytes>0 and file_size_bytes<=10485760),
 status text not null default 'submitted' check(status in ('submitted','approved','rejected')),
 reviewer_note text, reviewed_by uuid references auth.users(id), reviewed_at timestamptz, created_at timestamptz not null default now()
);
create index if not exists paysaathi_kyc_documents_user_idx on public.paysaathi_kyc_documents(user_id,created_at desc);
create table if not exists public.paysaathi_service_kyc_requirements (
 service_key text primary key, service_label text not null, kyc_required boolean not null default true,
 required_documents text[] not null default array['pan','identity_proof'], active boolean not null default true,
 updated_at timestamptz not null default now()
);
insert into public.paysaathi_service_kyc_requirements(service_key,service_label,kyc_required,required_documents)
values ('money_transfer','Money Transfer',true,array['pan','identity_proof']),('dmt','Domestic Money Transfer',true,array['pan','identity_proof']),('aeps','AEPS & Assisted Banking',true,array['pan','identity_proof']),('payout','Payout Services',true,array['pan','identity_proof']),('wallet_topup','Wallet Top-up',true,array['pan','identity_proof'])
on conflict(service_key) do nothing;
alter table public.paysaathi_kyc_profiles enable row level security;
alter table public.paysaathi_kyc_documents enable row level security;
alter table public.paysaathi_service_kyc_requirements enable row level security;
drop policy if exists "Users read own KYC profile" on public.paysaathi_kyc_profiles;
create policy "Users read own KYC profile" on public.paysaathi_kyc_profiles for select to authenticated using(auth.uid()=user_id or exists(select 1 from public.admin_users a where a.user_id=auth.uid() and a.active=true));
drop policy if exists "Users create own KYC profile" on public.paysaathi_kyc_profiles;
create policy "Users create own KYC profile" on public.paysaathi_kyc_profiles for insert to authenticated with check(auth.uid()=user_id);
drop policy if exists "Users update own unreviewed KYC profile" on public.paysaathi_kyc_profiles;
create policy "Users update own unreviewed KYC profile" on public.paysaathi_kyc_profiles for update to authenticated using(auth.uid()=user_id and status in('not_started','draft','rejected')) with check(auth.uid()=user_id and status in('draft','submitted'));
drop policy if exists "Admins update KYC profiles" on public.paysaathi_kyc_profiles;
create policy "Admins update KYC profiles" on public.paysaathi_kyc_profiles for update to authenticated using(exists(select 1 from public.admin_users a where a.user_id=auth.uid() and a.active=true)) with check(exists(select 1 from public.admin_users a where a.user_id=auth.uid() and a.active=true));
drop policy if exists "Users read own KYC documents" on public.paysaathi_kyc_documents;
create policy "Users read own KYC documents" on public.paysaathi_kyc_documents for select to authenticated using(auth.uid()=user_id or exists(select 1 from public.admin_users a where a.user_id=auth.uid() and a.active=true));
drop policy if exists "Users add own KYC documents" on public.paysaathi_kyc_documents;
create policy "Users add own KYC documents" on public.paysaathi_kyc_documents for insert to authenticated with check(auth.uid()=user_id and exists(select 1 from public.paysaathi_kyc_profiles p where p.user_id=auth.uid() and p.status in('not_started','draft','rejected')));
drop policy if exists "Admins review KYC documents" on public.paysaathi_kyc_documents;
create policy "Admins review KYC documents" on public.paysaathi_kyc_documents for update to authenticated using(exists(select 1 from public.admin_users a where a.user_id=auth.uid() and a.active=true)) with check(exists(select 1 from public.admin_users a where a.user_id=auth.uid() and a.active=true));
drop policy if exists "Authenticated read KYC service rules" on public.paysaathi_service_kyc_requirements;
create policy "Authenticated read KYC service rules" on public.paysaathi_service_kyc_requirements for select to authenticated using(active=true);
drop policy if exists "Admins manage KYC service rules" on public.paysaathi_service_kyc_requirements;
create policy "Admins manage KYC service rules" on public.paysaathi_service_kyc_requirements for all to authenticated using(exists(select 1 from public.admin_users a where a.user_id=auth.uid() and a.active=true)) with check(exists(select 1 from public.admin_users a where a.user_id=auth.uid() and a.active=true));
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types) values('paysaathi-kyc','paysaathi-kyc',false,10485760,array['image/jpeg','image/png','application/pdf']) on conflict(id) do update set public=false,file_size_limit=10485760,allowed_mime_types=array['image/jpeg','image/png','application/pdf'];
drop policy if exists "KYC users upload own files" on storage.objects;
create policy "KYC users upload own files" on storage.objects for insert to authenticated with check(bucket_id='paysaathi-kyc' and (storage.foldername(name))[1]=auth.uid()::text);
drop policy if exists "KYC users read own files and admins review" on storage.objects;
create policy "KYC users read own files and admins review" on storage.objects for select to authenticated using(bucket_id='paysaathi-kyc' and ((storage.foldername(name))[1]=auth.uid()::text or exists(select 1 from public.admin_users a where a.user_id=auth.uid() and a.active=true)));
drop policy if exists "KYC users delete own unsubmitted files" on storage.objects;
create policy "KYC users delete own unsubmitted files" on storage.objects for delete to authenticated using(bucket_id='paysaathi-kyc' and (storage.foldername(name))[1]=auth.uid()::text and not exists(select 1 from public.paysaathi_kyc_documents d where d.storage_path=name));
create or replace function public.paysaathi_kyc_access(p_service_key text) returns jsonb language plpgsql security definer set search_path=public,auth as $$
declare v_uid uuid:=auth.uid(); v_profile public.paysaathi_kyc_profiles%rowtype; v_rule public.paysaathi_service_kyc_requirements%rowtype; v_missing text[];
begin
 if v_uid is null then return jsonb_build_object('allowed',false,'reason','login_required'); end if;
 select * into v_rule from public.paysaathi_service_kyc_requirements where service_key=p_service_key and active=true;
 if not found or not v_rule.kyc_required then return jsonb_build_object('allowed',true,'status','not_required'); end if;
 select * into v_profile from public.paysaathi_kyc_profiles where user_id=v_uid;
 if not found or v_profile.status<>'approved' then return jsonb_build_object('allowed',false,'status',coalesce(v_profile.status,'not_started'),'reason','kyc_required','service_label',v_rule.service_label); end if;
 select array_agg(req) into v_missing from unnest(v_rule.required_documents) req where not exists(select 1 from public.paysaathi_kyc_documents d where d.user_id=v_uid and d.document_type=req and d.status='approved');
 if coalesce(array_length(v_missing,1),0)>0 then return jsonb_build_object('allowed',false,'status',v_profile.status,'reason','documents_missing','missing_documents',v_missing); end if;
 return jsonb_build_object('allowed',true,'status','approved');
end $$;
revoke all on function public.paysaathi_kyc_access(text) from public;
grant execute on function public.paysaathi_kyc_access(text) to authenticated;
