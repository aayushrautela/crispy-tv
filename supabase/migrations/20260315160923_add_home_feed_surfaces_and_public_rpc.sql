begin;

alter table public.home_feed_catalog rename to home_feed_kinds;

alter table public.home_feed_kinds
  rename constraint home_feed_catalog_pkey to home_feed_kinds_pkey;

alter table public.home_feed_kinds
  rename constraint home_feed_catalog_meta_object to home_feed_kinds_meta_object;

alter policy home_feed_catalog_select_authenticated on public.home_feed_kinds
  rename to home_feed_kinds_select_authenticated;

create table public.home_feed_surface_sections (
  surface text not null,
  kind text not null,
  variant_key text not null default 'default'::text,
  enabled boolean not null default true,
  order_index integer not null default 100,
  item_limit integer,
  meta jsonb not null default '{}'::jsonb,
  created_at timestamp with time zone not null default timezone('utc'::text, now()),
  updated_at timestamp with time zone not null default timezone('utc'::text, now()),
  constraint home_feed_surface_sections_pkey primary key (surface, kind, variant_key),
  constraint home_feed_surface_sections_surface_format_check check (
    char_length(surface) >= 1
    and char_length(surface) <= 80
    and surface ~ '^[a-z0-9]+(?:_[a-z0-9]+)*$'::text
  ),
  constraint home_feed_surface_sections_variant_key_format_check check (
    char_length(variant_key) >= 1
    and char_length(variant_key) <= 80
    and variant_key ~ '^[a-z0-9]+(?:_[a-z0-9]+)*$'::text
  ),
  constraint home_feed_surface_sections_item_limit_pos check (
    item_limit is null or item_limit > 0
  ),
  constraint home_feed_surface_sections_meta_object check (
    jsonb_typeof(meta) = 'object'::text
  ),
  constraint home_feed_surface_sections_kind_fkey foreign key (kind)
    references public.home_feed_kinds(kind) on delete cascade
);

create index home_feed_surface_sections_surface_enabled_order_idx
  on public.home_feed_surface_sections (surface, enabled, order_index, kind, variant_key);

insert into public.home_feed_surface_sections (
  surface,
  kind,
  variant_key,
  enabled,
  order_index,
  item_limit,
  created_at,
  updated_at
)
select
  'member_home'::text,
  hfk.kind,
  'default'::text,
  hfk.enabled,
  hfk.order_index,
  hfk.item_limit,
  hfk.created_at,
  hfk.updated_at
from public.home_feed_kinds hfk;

insert into public.home_feed_surface_sections (
  surface,
  kind,
  variant_key,
  enabled,
  order_index,
  item_limit,
  created_at,
  updated_at
)
select
  'public_home'::text,
  hfk.kind,
  'default'::text,
  hfk.enabled,
  hfk.order_index,
  hfk.item_limit,
  hfk.created_at,
  hfk.updated_at
from public.home_feed_kinds hfk;

alter table public.home_feed_payloads
  rename constraint home_feed_payloads_kind_catalog_fkey to home_feed_payloads_kind_fkey;

alter table public.home_feed_kinds
  drop constraint home_feed_catalog_item_limit_pos;

alter table public.home_feed_kinds
  drop column enabled,
  drop column order_index,
  drop column item_limit;

alter table public.home_feed_surface_sections enable row level security;

grant select on public.home_feed_surface_sections to authenticated;

create policy home_feed_surface_sections_select_authenticated
  on public.home_feed_surface_sections
  for select
  to authenticated
  using (true);

comment on table public.home_feed_kinds is 'Registry of reusable home feed rail kinds and shared metadata.';
comment on table public.home_feed_surface_sections is 'Placement, ordering, and per-surface limits for member and public home feeds.';

create or replace function public.normalize_home_feed_language_tag(p_language text)
returns text
language sql
immutable
set search_path to 'public'
as $function$
  select coalesce(
    nullif(
      regexp_replace(
        lower(replace(btrim(coalesce(p_language, 'all')), '-', '_')),
        '[^a-z0-9_]+'::text,
        ''::text,
        'g'
      ),
      ''::text
    ),
    'all'::text
  );
$function$;

create or replace function public.get_member_home_feed_section_payload(
  p_profile_id uuid,
  p_kind text,
  p_variant_key text default 'default',
  p_as_of date default current_date,
  p_limit integer default 50
)
returns jsonb
language plpgsql
set search_path to 'public'
as $function$
declare
  v_limit integer := greatest(0, least(coalesce(p_limit, 50), 200));
  v_variant_key text := coalesce(nullif(btrim(p_variant_key), ''), 'default');
  v_effective_as_of date;
  v_ratios jsonb;
  v_title text;
  v_subtitle text;
  v_results jsonb;
begin
  if not exists (
    select 1
    from public.profiles p
    where p.id = p_profile_id
      and public.is_household_member(p.household_id, auth.uid())
  ) then
    raise exception 'not allowed';
  end if;

  select max(hfp.as_of)
    into v_effective_as_of
  from public.home_feed_payloads hfp
  where hfp.kind = p_kind
    and hfp.variant_key = v_variant_key
    and hfp.as_of <= p_as_of;

  if v_effective_as_of is null then
    return jsonb_build_object(
      'kind', p_kind,
      'as_of', p_as_of,
      'page', 1,
      'total_pages', 1,
      'total_results', 0,
      'title', '',
      'subtitle', null,
      'results', '[]'::jsonb
    );
  end if;

  select pr.language_ratios
    into v_ratios
  from public.profile_recommendations pr
  where pr.profile_id = p_profile_id;

  if v_ratios is null or jsonb_typeof(v_ratios) <> 'object' then
    v_ratios := '{}'::jsonb;
  end if;

  select hfp.title, hfp.subtitle
    into v_title, v_subtitle
  from public.home_feed_payloads hfp
  where hfp.kind = p_kind
    and hfp.variant_key = v_variant_key
    and hfp.as_of = v_effective_as_of
    and hfp.language = 'all'
  limit 1;

  if v_title is null then
    select hfp.title, hfp.subtitle
      into v_title, v_subtitle
    from public.home_feed_payloads hfp
    where hfp.kind = p_kind
      and hfp.variant_key = v_variant_key
      and hfp.as_of = v_effective_as_of
    order by hfp.language
    limit 1;
  end if;

  with raw_weights as (
    select e.key as language,
           (e.value::text)::double precision as weight
    from jsonb_each(v_ratios) as e
    where jsonb_typeof(e.value) = 'number'
  ),
  weights as (
    select language, weight
    from raw_weights
    where weight > 0
  ),
  weights_or_default as (
    select * from weights
    union all
    select 'all'::text as language, 1::double precision as weight
    where not exists (select 1 from weights)
  ),
  lists as (
    select hfp.language,
           hfp.results,
           jsonb_array_length(hfp.results) as len,
           wod.weight
    from public.home_feed_payloads hfp
    join weights_or_default wod
      on wod.language = hfp.language
    where hfp.kind = p_kind
      and hfp.variant_key = v_variant_key
      and hfp.as_of = v_effective_as_of
  ),
  lists2 as (
    select * from lists
    union all
    select hfp.language,
           hfp.results,
           jsonb_array_length(hfp.results) as len,
           1::double precision as weight
    from public.home_feed_payloads hfp
    where hfp.kind = p_kind
      and hfp.variant_key = v_variant_key
      and hfp.as_of = v_effective_as_of
      and hfp.language = 'all'
      and not exists (select 1 from lists)
  ),
  schedule as (
    select l.language,
           gs.k as k,
           (gs.k::double precision / l.weight) as score
    from lists2 l
    join lateral generate_series(1, least(v_limit, l.len)) as gs(k)
      on true
  ),
  ordered as (
    select s.language, s.k, s.score
    from schedule s
    order by s.score asc, s.language asc, s.k asc
    limit v_limit
  ),
  picked as (
    select row_number() over (order by o.score, o.language, o.k) as seq_raw,
           (l.results -> (o.k - 1)) as item
    from ordered o
    join lists2 l
      on l.language = o.language
    where (l.results -> (o.k - 1)) is not null
  ),
  dedup as (
    select row_number() over (order by seq_raw) as seq,
           item
    from (
      select p.*,
             row_number() over (
               partition by (p.item ->> 'media_type'), (p.item ->> 'id')
               order by p.seq_raw
             ) as rn
      from picked p
    ) t
    where rn = 1
    order by seq_raw
    limit v_limit
  ),
  base_count as (
    select count(*)::integer as n
    from dedup
  ),
  used as (
    select (d.item ->> 'media_type') as media_type,
           (d.item ->> 'id') as id
    from dedup d
  ),
  filler as (
    select (bc.n + row_number() over (order by ord)) as seq,
           e.elem as item
    from base_count bc
    join public.home_feed_payloads hfp
      on hfp.kind = p_kind
     and hfp.variant_key = v_variant_key
     and hfp.as_of = v_effective_as_of
     and hfp.language = 'all'
    join lateral jsonb_array_elements(hfp.results) with ordinality as e(elem, ord)
      on true
    left join used u
      on u.media_type = (e.elem ->> 'media_type')
     and u.id = (e.elem ->> 'id')
    where bc.n < v_limit
      and u.id is null
    order by ord
    limit (v_limit - (select n from base_count))
  ),
  merged as (
    select seq, item from dedup
    union all
    select seq, item from filler
  )
  select coalesce(jsonb_agg(item order by seq), '[]'::jsonb)
    into v_results
  from merged;

  if v_results is null then
    v_results := '[]'::jsonb;
  end if;

  return jsonb_build_object(
    'kind', p_kind,
    'as_of', v_effective_as_of,
    'page', 1,
    'total_pages', 1,
    'total_results', jsonb_array_length(v_results),
    'title', coalesce(v_title, ''),
    'subtitle', v_subtitle,
    'results', v_results
  );
end;
$function$;

create or replace function public.get_member_home_feed(
  p_profile_id uuid,
  p_as_of date default current_date,
  p_limit integer default 50
)
returns jsonb
language sql
set search_path to 'public'
as $function$
  with defs as (
    select
      hfss.kind,
      hfss.variant_key,
      hfss.order_index,
      coalesce(hfss.item_limit, p_limit) as item_limit
    from public.home_feed_surface_sections hfss
    where hfss.surface = 'member_home'
      and hfss.enabled = true
  ),
  sections as (
    select
      d.order_index,
      d.kind,
      d.variant_key,
      public.get_member_home_feed_section_payload(
        p_profile_id,
        d.kind,
        d.variant_key,
        p_as_of,
        d.item_limit
      ) as section_json
    from defs d
  )
  select coalesce(
    jsonb_agg(section_json order by order_index, kind, variant_key),
    '[]'::jsonb
  )
  from sections
  where jsonb_array_length(section_json->'results') > 0;
$function$;

create or replace function public.get_public_home_feed_section_payload(
  p_language text,
  p_kind text,
  p_variant_key text default 'default',
  p_as_of date default current_date,
  p_limit integer default 50
)
returns jsonb
language plpgsql
security definer
set search_path to 'public'
as $function$
declare
  v_limit integer := greatest(0, least(coalesce(p_limit, 50), 200));
  v_variant_key text := coalesce(nullif(btrim(p_variant_key), ''), 'default');
  v_requested_language text := public.normalize_home_feed_language_tag(p_language);
  v_base_language text := split_part(public.normalize_home_feed_language_tag(p_language), '_'::text, 1);
  v_effective_as_of date;
  v_preferred_language text;
  v_title text;
  v_subtitle text;
  v_results jsonb;
begin
  select max(hfp.as_of)
    into v_effective_as_of
  from public.home_feed_payloads hfp
  where hfp.kind = p_kind
    and hfp.variant_key = v_variant_key
    and hfp.as_of <= p_as_of;

  if v_effective_as_of is null then
    return jsonb_build_object(
      'kind', p_kind,
      'as_of', p_as_of,
      'page', 1,
      'total_pages', 1,
      'total_results', 0,
      'title', '',
      'subtitle', null,
      'results', '[]'::jsonb
    );
  end if;

  with candidates as (
    select distinct
      hfp.language,
      case
        when hfp.language = v_requested_language then 1
        when hfp.language = v_base_language then 2
        when hfp.language = 'all' then 3
        else 4
      end as priority
    from public.home_feed_payloads hfp
    where hfp.kind = p_kind
      and hfp.variant_key = v_variant_key
      and hfp.as_of = v_effective_as_of
  )
  select c.language
    into v_preferred_language
  from candidates c
  order by c.priority, c.language
  limit 1;

  if v_preferred_language is null then
    return jsonb_build_object(
      'kind', p_kind,
      'as_of', v_effective_as_of,
      'page', 1,
      'total_pages', 1,
      'total_results', 0,
      'title', '',
      'subtitle', null,
      'results', '[]'::jsonb
    );
  end if;

  select hfp.title, hfp.subtitle
    into v_title, v_subtitle
  from public.home_feed_payloads hfp
  where hfp.kind = p_kind
    and hfp.variant_key = v_variant_key
    and hfp.as_of = v_effective_as_of
    and hfp.language = v_preferred_language
  limit 1;

  if v_title is null and v_preferred_language <> 'all' then
    select hfp.title, hfp.subtitle
      into v_title, v_subtitle
    from public.home_feed_payloads hfp
    where hfp.kind = p_kind
      and hfp.variant_key = v_variant_key
      and hfp.as_of = v_effective_as_of
      and hfp.language = 'all'
    limit 1;
  end if;

  if v_title is null then
    select hfp.title, hfp.subtitle
      into v_title, v_subtitle
    from public.home_feed_payloads hfp
    where hfp.kind = p_kind
      and hfp.variant_key = v_variant_key
      and hfp.as_of = v_effective_as_of
    order by hfp.language
    limit 1;
  end if;

  with selected as (
    select hfp.results
    from public.home_feed_payloads hfp
    where hfp.kind = p_kind
      and hfp.variant_key = v_variant_key
      and hfp.as_of = v_effective_as_of
      and hfp.language = v_preferred_language
    limit 1
  ),
  selected_items as (
    select row_number() over (order by ord) as seq,
           e.elem as item
    from selected s
    join lateral jsonb_array_elements(s.results) with ordinality as e(elem, ord)
      on true
    limit v_limit
  ),
  base_count as (
    select count(*)::integer as n
    from selected_items
  ),
  used as (
    select (si.item ->> 'media_type') as media_type,
           (si.item ->> 'id') as id
    from selected_items si
  ),
  filler as (
    select (bc.n + row_number() over (order by ord)) as seq,
           e.elem as item
    from base_count bc
    join public.home_feed_payloads hfp
      on hfp.kind = p_kind
     and hfp.variant_key = v_variant_key
     and hfp.as_of = v_effective_as_of
     and hfp.language = 'all'
    join lateral jsonb_array_elements(hfp.results) with ordinality as e(elem, ord)
      on true
    left join used u
      on u.media_type = (e.elem ->> 'media_type')
     and u.id = (e.elem ->> 'id')
    where v_preferred_language <> 'all'
      and bc.n < v_limit
      and u.id is null
    order by ord
    limit (v_limit - (select n from base_count))
  ),
  merged as (
    select seq, item from selected_items
    union all
    select seq, item from filler
  )
  select coalesce(jsonb_agg(item order by seq), '[]'::jsonb)
    into v_results
  from merged;

  if v_results is null then
    v_results := '[]'::jsonb;
  end if;

  return jsonb_build_object(
    'kind', p_kind,
    'as_of', v_effective_as_of,
    'page', 1,
    'total_pages', 1,
    'total_results', jsonb_array_length(v_results),
    'title', coalesce(v_title, ''),
    'subtitle', v_subtitle,
    'results', v_results
  );
end;
$function$;

create or replace function public.get_public_home_feed(
  p_language text default 'all',
  p_as_of date default current_date,
  p_limit integer default 50
)
returns jsonb
language sql
security definer
set search_path to 'public'
as $function$
  with defs as (
    select
      hfss.kind,
      hfss.variant_key,
      hfss.order_index,
      coalesce(hfss.item_limit, p_limit) as item_limit
    from public.home_feed_surface_sections hfss
    where hfss.surface = 'public_home'
      and hfss.enabled = true
  ),
  sections as (
    select
      d.order_index,
      d.kind,
      d.variant_key,
      public.get_public_home_feed_section_payload(
        p_language,
        d.kind,
        d.variant_key,
        p_as_of,
        d.item_limit
      ) as section_json
    from defs d
  )
  select coalesce(
    jsonb_agg(section_json order by order_index, kind, variant_key),
    '[]'::jsonb
  )
  from sections
  where jsonb_array_length(section_json->'results') > 0;
$function$;

create or replace function public.get_profile_recommendations(p_profile_id uuid)
returns jsonb
language sql
stable
set search_path to 'public'
as $function$
  select jsonb_build_object(
    'profile_id', r.profile_id,
    'generated_at', r.generated_at,
    'source_last_watched_at', r.source_last_watched_at,
    'algo_version', r.algo_version,
    'lists', coalesce(
      (
        select jsonb_agg(
          jsonb_set(
            list_elem,
            '{results}',
            coalesce(
              (
                select jsonb_agg(
                  case
                    when item->>'media_type' in ('movie', 'tv')
                      and (item->>'id') ~ '^[0-9]+$'
                    then jsonb_set(
                      item,
                      '{read}',
                      to_jsonb(coalesce(s.read, false)),
                      true
                    )
                    else item
                  end
                  order by item_ord
                )
                from jsonb_array_elements(coalesce(list_elem->'results', '[]'::jsonb)) with ordinality as item_rows(item, item_ord)
                left join public.profile_title_state s
                  on s.profile_id = r.profile_id
                 and s.media_type = item_rows.item->>'media_type'
                 and s.tmdb_id = (item_rows.item->>'id')::integer
              ),
              '[]'::jsonb
            ),
            true
          )
          order by list_ord
        )
        from jsonb_array_elements(coalesce(r.lists, '[]'::jsonb)) with ordinality as list_rows(list_elem, list_ord)
      ),
      '[]'::jsonb
    )
  )
  from public.profile_recommendations r
  where r.profile_id = p_profile_id;
$function$;

create or replace function public.get_home_feed_section_payload(
  p_profile_id uuid,
  p_kind text,
  p_as_of date default current_date,
  p_limit integer default 50
)
returns jsonb
language sql
set search_path to 'public'
as $function$
  select public.get_member_home_feed_section_payload(
    p_profile_id,
    p_kind,
    'default',
    p_as_of,
    p_limit
  );
$function$;

create or replace function public.get_home_feed(
  p_profile_id uuid,
  p_as_of date default current_date,
  p_limit integer default 50
)
returns jsonb
language sql
set search_path to 'public'
as $function$
  select public.get_member_home_feed(p_profile_id, p_as_of, p_limit);
$function$;

create or replace function public.get_mixed_global_list(
  p_profile_id uuid,
  p_kind text,
  p_as_of date default current_date,
  p_limit integer default 50
)
returns jsonb
language sql
set search_path to 'public'
as $function$
  select public.get_member_home_feed_section_payload(
    p_profile_id,
    p_kind,
    'default',
    p_as_of,
    p_limit
  );
$function$;

create or replace function public.get_global_lists_feed(
  p_profile_id uuid,
  p_as_of date default current_date,
  p_limit integer default 50
)
returns jsonb
language sql
set search_path to 'public'
as $function$
  select public.get_member_home_feed(p_profile_id, p_as_of, p_limit);
$function$;

comment on function public.normalize_home_feed_language_tag(text) is 'Normalizes requested public feed language tags to lowercase underscore-separated tokens.';
comment on function public.get_member_home_feed_section_payload(uuid, text, text, date, integer) is 'Builds one member-only shared home feed rail using profile language ratios and shared payloads.';
comment on function public.get_member_home_feed(uuid, date, integer) is 'Builds the ordered member-shared home feed from the surface layout and shared payloads.';
comment on function public.get_public_home_feed_section_payload(text, text, text, date, integer) is 'Builds one public home feed rail using locale-aware fallback over shared payloads.';
comment on function public.get_public_home_feed(text, date, integer) is 'Builds the ordered public home feed from the public surface layout and shared payloads.';
comment on function public.get_profile_recommendations(uuid) is 'Returns one personalized profile recommendation payload with read-state enrichment.';
comment on function public.get_home_feed_section_payload(uuid, text, date, integer) is 'Legacy wrapper for the member-shared home feed section payload RPC.';
comment on function public.get_home_feed(uuid, date, integer) is 'Legacy wrapper for the member-shared home feed RPC.';

revoke all on function public.normalize_home_feed_language_tag(text) from public;

revoke all on function public.get_member_home_feed_section_payload(uuid, text, text, date, integer) from public;
revoke all on function public.get_member_home_feed_section_payload(uuid, text, text, date, integer) from anon;
grant execute on function public.get_member_home_feed_section_payload(uuid, text, text, date, integer) to authenticated;

revoke all on function public.get_member_home_feed(uuid, date, integer) from public;
revoke all on function public.get_member_home_feed(uuid, date, integer) from anon;
grant execute on function public.get_member_home_feed(uuid, date, integer) to authenticated;

revoke all on function public.get_public_home_feed_section_payload(text, text, text, date, integer) from public;
grant execute on function public.get_public_home_feed_section_payload(text, text, text, date, integer) to anon;
grant execute on function public.get_public_home_feed_section_payload(text, text, text, date, integer) to authenticated;

revoke all on function public.get_public_home_feed(text, date, integer) from public;
grant execute on function public.get_public_home_feed(text, date, integer) to anon;
grant execute on function public.get_public_home_feed(text, date, integer) to authenticated;

revoke all on function public.get_profile_recommendations(uuid) from public;
revoke all on function public.get_profile_recommendations(uuid) from anon;
revoke all on function public.get_profile_recommendations(uuid) from service_role;
grant execute on function public.get_profile_recommendations(uuid) to authenticated;

revoke all on function public.get_home_feed_section_payload(uuid, text, date, integer) from public;
revoke all on function public.get_home_feed_section_payload(uuid, text, date, integer) from anon;
grant execute on function public.get_home_feed_section_payload(uuid, text, date, integer) to authenticated;

revoke all on function public.get_home_feed(uuid, date, integer) from public;
revoke all on function public.get_home_feed(uuid, date, integer) from anon;
grant execute on function public.get_home_feed(uuid, date, integer) to authenticated;

revoke all on function public.get_mixed_global_list(uuid, text, date, integer) from public;
revoke all on function public.get_mixed_global_list(uuid, text, date, integer) from anon;
grant execute on function public.get_mixed_global_list(uuid, text, date, integer) to authenticated;

revoke all on function public.get_global_lists_feed(uuid, date, integer) from public;
revoke all on function public.get_global_lists_feed(uuid, date, integer) from anon;
grant execute on function public.get_global_lists_feed(uuid, date, integer) to authenticated;

commit;
