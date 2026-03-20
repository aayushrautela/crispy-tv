begin;

delete from private.provider_credentials;
delete from public.provider_accounts;

alter table public.provider_accounts
  drop column if exists access_token;

drop policy if exists provider_accounts_select_member on public.provider_accounts;
create policy provider_accounts_select_member
on public.provider_accounts
for select
to authenticated
using (
  exists (
    select 1
    from public.profiles p
    join public.household_members hm on hm.household_id = p.household_id
    where p.id = provider_accounts.profile_id
      and hm.user_id = (select auth.uid())
  )
);

alter table private.provider_credentials
  drop column if exists provider_user_id,
  drop column if exists provider_username,
  drop column if exists last_refresh_error;

create index if not exists provider_credentials_provider_expiry_idx
on private.provider_credentials (provider, access_token_expires_at)
where refresh_token is not null and btrim(refresh_token) <> '';

create index if not exists provider_credentials_last_refresh_idx
on private.provider_credentials (last_refresh_at);

create or replace function public.internal_upsert_provider_session(
  p_profile_id uuid,
  p_provider text,
  p_access_token text,
  p_access_token_expires_at timestamp with time zone,
  p_provider_user_id text,
  p_provider_username text,
  p_refresh_token text,
  p_connected_at timestamp with time zone,
  p_last_refresh_at timestamp with time zone,
  p_last_refresh_error text
)
returns table (
  access_token text,
  access_token_expires_at timestamp with time zone,
  provider_username text,
  provider_user_id text,
  connected_at timestamp with time zone
)
language plpgsql
security definer
set search_path to 'public', 'private'
as $function$
begin
  insert into public.provider_accounts (
    profile_id,
    provider,
    access_token_expires_at,
    provider_user_id,
    provider_username,
    connected_at,
    last_refresh_at,
    last_refresh_error
  )
  values (
    p_profile_id,
    p_provider,
    p_access_token_expires_at,
    p_provider_user_id,
    p_provider_username,
    coalesce(p_connected_at, timezone('utc', now())),
    p_last_refresh_at,
    p_last_refresh_error
  )
  on conflict (profile_id, provider) do update
  set
    access_token_expires_at = excluded.access_token_expires_at,
    provider_user_id = coalesce(excluded.provider_user_id, provider_accounts.provider_user_id),
    provider_username = coalesce(excluded.provider_username, provider_accounts.provider_username),
    last_refresh_at = excluded.last_refresh_at,
    last_refresh_error = excluded.last_refresh_error;

  insert into private.provider_credentials (
    profile_id,
    provider,
    refresh_token,
    access_token,
    access_token_expires_at,
    last_refresh_at
  )
  values (
    p_profile_id,
    p_provider,
    p_refresh_token,
    p_access_token,
    p_access_token_expires_at,
    p_last_refresh_at
  )
  on conflict (profile_id, provider) do update
  set
    refresh_token = coalesce(excluded.refresh_token, provider_credentials.refresh_token),
    access_token = excluded.access_token,
    access_token_expires_at = excluded.access_token_expires_at,
    last_refresh_at = excluded.last_refresh_at;

  return query
  select
    p_access_token,
    pa.access_token_expires_at,
    pa.provider_username,
    pa.provider_user_id,
    pa.connected_at
  from public.provider_accounts pa
  where pa.profile_id = p_profile_id
    and pa.provider = p_provider;
end;
$function$;

create or replace function public.internal_get_provider_credentials(
  p_profile_id uuid,
  p_provider text
)
returns table (
  profile_id uuid,
  provider text,
  refresh_token text,
  access_token text,
  access_token_expires_at timestamp with time zone,
  provider_user_id text,
  provider_username text,
  connected_at timestamp with time zone,
  last_refresh_at timestamp with time zone,
  last_refresh_error text
)
language sql
security definer
set search_path to 'public', 'private'
as $function$
  select
    pc.profile_id,
    pc.provider,
    pc.refresh_token,
    pc.access_token,
    pc.access_token_expires_at,
    pa.provider_user_id,
    pa.provider_username,
    pa.connected_at,
    pa.last_refresh_at,
    pa.last_refresh_error
  from private.provider_credentials pc
  left join public.provider_accounts pa
    on pa.profile_id = pc.profile_id
   and pa.provider = pc.provider
  where pc.profile_id = p_profile_id
    and pc.provider = p_provider;
$function$;

create or replace function public.internal_set_provider_refresh_error(
  p_profile_id uuid,
  p_provider text,
  p_last_refresh_at timestamp with time zone,
  p_last_refresh_error text
)
returns void
language plpgsql
security definer
set search_path to 'public', 'private'
as $function$
begin
  update public.provider_accounts
  set
    last_refresh_at = p_last_refresh_at,
    last_refresh_error = p_last_refresh_error
  where profile_id = p_profile_id
    and provider = p_provider;

  update private.provider_credentials
  set last_refresh_at = p_last_refresh_at
  where profile_id = p_profile_id
    and provider = p_provider;
end;
$function$;

create or replace function public.internal_delete_provider_session(
  p_profile_id uuid,
  p_provider text
)
returns void
language plpgsql
security definer
set search_path to 'public', 'private'
as $function$
begin
  delete from private.provider_credentials
  where profile_id = p_profile_id
    and provider = p_provider;

  delete from public.provider_accounts
  where profile_id = p_profile_id
    and provider = p_provider;
end;
$function$;

revoke all on function public.internal_upsert_provider_session(uuid, text, text, timestamp with time zone, text, text, text, timestamp with time zone, timestamp with time zone, text) from public;
revoke all on function public.internal_upsert_provider_session(uuid, text, text, timestamp with time zone, text, text, text, timestamp with time zone, timestamp with time zone, text) from anon;
revoke all on function public.internal_upsert_provider_session(uuid, text, text, timestamp with time zone, text, text, text, timestamp with time zone, timestamp with time zone, text) from authenticated;
grant execute on function public.internal_upsert_provider_session(uuid, text, text, timestamp with time zone, text, text, text, timestamp with time zone, timestamp with time zone, text) to service_role;

revoke all on function public.internal_get_provider_credentials(uuid, text) from public;
revoke all on function public.internal_get_provider_credentials(uuid, text) from anon;
revoke all on function public.internal_get_provider_credentials(uuid, text) from authenticated;
grant execute on function public.internal_get_provider_credentials(uuid, text) to service_role;

revoke all on function public.internal_set_provider_refresh_error(uuid, text, timestamp with time zone, text) from public;
revoke all on function public.internal_set_provider_refresh_error(uuid, text, timestamp with time zone, text) from anon;
revoke all on function public.internal_set_provider_refresh_error(uuid, text, timestamp with time zone, text) from authenticated;
grant execute on function public.internal_set_provider_refresh_error(uuid, text, timestamp with time zone, text) to service_role;

revoke all on function public.internal_delete_provider_session(uuid, text) from public;
revoke all on function public.internal_delete_provider_session(uuid, text) from anon;
revoke all on function public.internal_delete_provider_session(uuid, text) from authenticated;
grant execute on function public.internal_delete_provider_session(uuid, text) to service_role;

commit;
