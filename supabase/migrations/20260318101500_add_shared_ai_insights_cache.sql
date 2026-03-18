begin;

create table public.ai_insights_cache (
  tmdb_id integer not null,
  media_type text not null,
  locale text not null,
  generation_version text not null,
  model_name text not null,
  payload jsonb not null,
  generated_by_profile_id uuid references public.profiles(id) on delete set null,
  created_at timestamp with time zone not null default timezone('utc'::text, now()),
  updated_at timestamp with time zone not null default timezone('utc'::text, now()),
  constraint ai_insights_cache_pkey primary key (tmdb_id, media_type, locale, generation_version),
  constraint ai_insights_cache_tmdb_id_pos check (tmdb_id > 0),
  constraint ai_insights_cache_media_type_check check (media_type in ('movie'::text, 'tv'::text)),
  constraint ai_insights_cache_locale_format_check check (
    char_length(locale) >= 2
    and char_length(locale) <= 35
    and locale ~ '^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,3}$'::text
  ),
  constraint ai_insights_cache_generation_version_check check (char_length(btrim(generation_version)) >= 1),
  constraint ai_insights_cache_model_name_check check (char_length(btrim(model_name)) >= 1),
  constraint ai_insights_cache_payload_object check (jsonb_typeof(payload) = 'object'::text),
  constraint ai_insights_cache_payload_insights_array check (jsonb_typeof(payload -> 'insights') = 'array'::text),
  constraint ai_insights_cache_payload_trivia_string check (jsonb_typeof(payload -> 'trivia') = 'string'::text)
);

comment on table public.ai_insights_cache is 'Shared AI insights cache keyed by TMDB title, locale, and generation version.';
comment on column public.ai_insights_cache.payload is 'JSON payload returned to clients, including insight cards and trivia.';

create index ai_insights_cache_lookup_idx
  on public.ai_insights_cache (tmdb_id, media_type, locale, generation_version);

alter table public.ai_insights_cache enable row level security;

grant select, insert, update on table public.ai_insights_cache to service_role;

revoke all on table public.ai_insights_cache from anon;
revoke all on table public.ai_insights_cache from authenticated;

create trigger set_ai_insights_cache_updated_at
before update on public.ai_insights_cache
for each row
execute function public.set_current_timestamp_updated_at();

commit;
