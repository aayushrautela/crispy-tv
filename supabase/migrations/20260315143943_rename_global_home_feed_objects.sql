begin;

alter table public.global_list_catalog rename to home_feed_catalog;
alter table public.global_lists rename to home_feed_payloads;

alter table public.home_feed_catalog rename constraint global_list_catalog_item_limit_pos to home_feed_catalog_item_limit_pos;
alter table public.home_feed_catalog rename constraint global_list_catalog_meta_object to home_feed_catalog_meta_object;
alter table public.home_feed_catalog rename constraint global_list_catalog_pkey to home_feed_catalog_pkey;

alter table public.home_feed_payloads rename constraint global_lists_kind_catalog_fkey to home_feed_payloads_kind_catalog_fkey;
alter table public.home_feed_payloads rename constraint global_lists_kind_format_check to home_feed_payloads_kind_format_check;
alter table public.home_feed_payloads rename constraint global_lists_language_format_check to home_feed_payloads_language_format_check;
alter table public.home_feed_payloads rename constraint global_lists_meta_object to home_feed_payloads_meta_object;
alter table public.home_feed_payloads rename constraint global_lists_pkey to home_feed_payloads_pkey;
alter table public.home_feed_payloads rename constraint global_lists_results_array to home_feed_payloads_results_array;
alter table public.home_feed_payloads rename constraint global_lists_variant_key_format_check to home_feed_payloads_variant_key_format_check;

alter index public.global_lists_kind_variant_language_uidx rename to home_feed_payloads_kind_variant_language_uidx;

alter policy global_list_catalog_select_authenticated on public.home_feed_catalog rename to home_feed_catalog_select_authenticated;
alter policy global_lists_select_authenticated on public.home_feed_payloads rename to home_feed_payloads_select_authenticated;

comment on table public.home_feed_catalog is 'Registry of home feed rail definitions, ordering, and per-rail limits.';
comment on table public.home_feed_payloads is 'Current stored payloads for each home feed rail kind, variant, and language.';

create or replace function public.get_home_feed_section_payload(
  p_profile_id uuid,
  p_kind text,
  p_as_of date default current_date,
  p_limit integer default 50
)
returns jsonb
language plpgsql
set search_path to 'public'
as $function$
declare
  v_limit integer := greatest(0, least(coalesce(p_limit, 50), 200));
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
    and hfp.as_of = v_effective_as_of
    and hfp.language = 'all'
  limit 1;

  if v_title is null then
    select hfp.title, hfp.subtitle
      into v_title, v_subtitle
    from public.home_feed_payloads hfp
    where hfp.kind = p_kind
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

create or replace function public.get_home_feed(
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
      hfc.kind,
      hfc.order_index,
      coalesce(hfc.item_limit, p_limit) as item_limit
    from public.home_feed_catalog hfc
    where hfc.enabled = true
  ),
  sections as (
    select
      d.order_index,
      d.kind,
      public.get_home_feed_section_payload(p_profile_id, d.kind, p_as_of, d.item_limit) as section_json
    from defs d
  )
  select coalesce(
    jsonb_agg(section_json order by order_index, kind),
    '[]'::jsonb
  )
  from sections
  where jsonb_array_length(section_json->'results') > 0;
$function$;

comment on function public.get_home_feed_section_payload(uuid, text, date, integer) is 'Builds one personalized home feed rail payload from stored global feed data.';
comment on function public.get_home_feed(uuid, date, integer) is 'Builds the ordered personalized home feed from the configured rail catalog and payloads.';

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
  select public.get_home_feed_section_payload(p_profile_id, p_kind, p_as_of, p_limit);
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
  select public.get_home_feed(p_profile_id, p_as_of, p_limit);
$function$;

grant execute on function public.get_home_feed_section_payload(uuid, text, date, integer) to public;
grant execute on function public.get_home_feed_section_payload(uuid, text, date, integer) to authenticated;
grant execute on function public.get_home_feed(uuid, date, integer) to public;
grant execute on function public.get_home_feed(uuid, date, integer) to authenticated;

commit;
