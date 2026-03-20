begin;

alter table public.profile_data
  drop constraint if exists profile_data_trakt_auth_check;

alter table public.profile_data
  drop constraint if exists profile_data_simkl_auth_check;

alter table public.profile_data
  drop column if exists trakt_auth,
  drop column if exists simkl_auth;

create or replace function public.upsert_profile_data(
  p_profile_id uuid,
  p_settings jsonb default '{}'::jsonb,
  p_catalog_prefs jsonb default '{}'::jsonb
)
returns void
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
  v_user_id uuid;
  v_is_member boolean;
begin
  v_user_id := auth.uid();
  if v_user_id is null then
    raise exception 'Not authenticated';
  end if;

  p_settings := coalesce(p_settings, '{}'::jsonb);
  p_catalog_prefs := coalesce(p_catalog_prefs, '{}'::jsonb);

  if not public.is_string_map(p_settings) then
    raise exception 'p_settings must be an object of string->string';
  end if;
  if not public.is_string_map(p_catalog_prefs) then
    raise exception 'p_catalog_prefs must be an object of string->string';
  end if;

  select exists (
    select 1
    from public.profiles p
    join public.household_members hm on hm.household_id = p.household_id
    where p.id = p_profile_id
      and hm.user_id = v_user_id
  ) into v_is_member;

  if v_is_member is distinct from true then
    raise exception 'Not authorized for profile';
  end if;

  insert into public.profile_data(profile_id, settings, catalog_prefs, version)
  values (p_profile_id, p_settings, p_catalog_prefs, 1)
  on conflict (profile_id) do update set
    settings = excluded.settings,
    catalog_prefs = excluded.catalog_prefs,
    version = public.profile_data.version + 1;
end;
$function$;

commit;
