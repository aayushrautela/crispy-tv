begin;

drop function if exists public.upsert_profile_data(uuid, jsonb, jsonb, jsonb, jsonb);

commit;
