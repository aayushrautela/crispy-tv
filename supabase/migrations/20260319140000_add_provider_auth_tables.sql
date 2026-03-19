begin;

create schema if not exists private;

revoke all on schema private from public;
revoke all on schema private from anon;
revoke all on schema private from authenticated;
grant usage on schema private to service_role;

create table if not exists public.provider_accounts (
  profile_id uuid not null references public.profiles(id) on delete cascade,
  provider text not null check (provider in ('trakt', 'simkl')),
  access_token text not null,
  access_token_expires_at timestamp with time zone,
  provider_user_id text,
  provider_username text,
  connected_at timestamp with time zone not null default timezone('utc', now()),
  last_refresh_at timestamp with time zone,
  last_refresh_error text,
  created_at timestamp with time zone not null default timezone('utc', now()),
  updated_at timestamp with time zone not null default timezone('utc', now()),
  primary key (profile_id, provider)
);

alter table public.provider_accounts enable row level security;

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
      and hm.user_id = auth.uid()
  )
);

grant select on public.provider_accounts to authenticated;
revoke all on public.provider_accounts from anon;
grant all on public.provider_accounts to service_role;

drop trigger if exists provider_accounts_set_updated_at on public.provider_accounts;
create trigger provider_accounts_set_updated_at
before update on public.provider_accounts
for each row
execute function public.set_current_timestamp_updated_at();

create table if not exists private.provider_credentials (
  profile_id uuid not null references public.profiles(id) on delete cascade,
  provider text not null check (provider in ('trakt', 'simkl')),
  refresh_token text,
  access_token text,
  access_token_expires_at timestamp with time zone,
  provider_user_id text,
  provider_username text,
  last_refresh_at timestamp with time zone,
  last_refresh_error text,
  created_at timestamp with time zone not null default timezone('utc', now()),
  updated_at timestamp with time zone not null default timezone('utc', now()),
  primary key (profile_id, provider)
);

revoke all on private.provider_credentials from public;
revoke all on private.provider_credentials from anon;
revoke all on private.provider_credentials from authenticated;
grant all on private.provider_credentials to service_role;

drop trigger if exists provider_credentials_set_updated_at on private.provider_credentials;
create trigger provider_credentials_set_updated_at
before update on private.provider_credentials
for each row
execute function public.set_current_timestamp_updated_at();

commit;
