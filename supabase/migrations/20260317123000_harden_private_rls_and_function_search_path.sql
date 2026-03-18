begin;

alter policy household_members_delete_owner on public.household_members to authenticated;
alter policy household_members_insert_owner on public.household_members to authenticated;
alter policy household_members_select_member on public.household_members to authenticated;
alter policy household_members_update_owner on public.household_members to authenticated;

alter policy households_delete_owner on public.households to authenticated;
alter policy households_select_member on public.households to authenticated;
alter policy households_update_owner on public.households to authenticated;

alter policy profile_data_delete_member on public.profile_data to authenticated;
alter policy profile_data_insert_member on public.profile_data to authenticated;
alter policy profile_data_select_member on public.profile_data to authenticated;
alter policy profile_data_update_member on public.profile_data to authenticated;

alter policy profile_recommendations_select_household on public.profile_recommendations to authenticated;

alter policy profile_title_state_delete_household on public.profile_title_state to authenticated;
alter policy profile_title_state_insert_household on public.profile_title_state to authenticated;
alter policy profile_title_state_select_household on public.profile_title_state to authenticated;
alter policy profile_title_state_update_household on public.profile_title_state to authenticated;

alter policy profiles_delete_member on public.profiles to authenticated;
alter policy profiles_insert_member on public.profiles to authenticated;
alter policy profiles_select_member on public.profiles to authenticated;
alter policy profiles_update_member on public.profiles to authenticated;

revoke all on table public.household_members from anon;
revoke all on table public.households from anon;
revoke all on table public.profile_data from anon;
revoke all on table public.profile_recommendations from anon;
revoke all on table public.profile_title_state from anon;
revoke all on table public.profiles from anon;

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path to 'public'
as $function$
begin
    insert into public.user_data (user_id)
    values (new.id)
    on conflict (user_id) do nothing;
    return new;
end;
$function$;

create or replace function public.is_string_map(payload jsonb)
returns boolean
language sql
immutable
set search_path to 'public'
as $function$
  select
    payload is not null
    and jsonb_typeof(payload) = 'object'
    and coalesce((
      select bool_and(jsonb_typeof(value) = 'string')
      from jsonb_each(payload)
    ), true);
$function$;

create or replace function public.normalize_household_addon_row()
returns trigger
language plpgsql
set search_path to 'public'
as $function$
begin
  new.url := regexp_replace(btrim(new.url), '/+$', '');
  if new.url is null or new.url = '' then
    raise exception 'url is required';
  end if;

  new.enabled := coalesce(new.enabled, true);

  if new.name is not null then
    new.name := nullif(btrim(new.name), '');
  end if;

  return new;
end;
$function$;

create or replace function public.normalize_profile_name(raw_name text, fallback_name text default 'user'::text)
returns text
language plpgsql
immutable
set search_path to 'public'
as $function$
declare
  candidate text;
begin
  candidate := lower(coalesce(nullif(btrim(raw_name), ''), nullif(btrim(fallback_name), ''), 'user'));
  candidate := regexp_replace(candidate, '[^a-z0-9._\- ]', '', 'g');
  candidate := regexp_replace(candidate, '\s+', ' ', 'g');
  candidate := btrim(candidate, ' ._-');
  if candidate = '' then
    candidate := 'user';
  end if;
  return left(candidate, 32);
end;
$function$;

create or replace function public.set_current_timestamp_updated_at()
returns trigger
language plpgsql
set search_path to 'public'
as $function$
begin
  new.updated_at = timezone('utc', now());
  return new;
end;
$function$;

commit;
