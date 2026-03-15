begin;

revoke select on table public.profile_recommendations from anon;

commit;
