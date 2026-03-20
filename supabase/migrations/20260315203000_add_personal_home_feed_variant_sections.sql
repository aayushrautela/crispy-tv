begin;

insert into public.home_feed_surface_sections (
  surface,
  kind,
  variant_key,
  enabled,
  order_index,
  item_limit,
  meta
)
values
  ('personal_home', 'hero_similar', 'default', true, 10, null, '{"source":"personal","presentation":"hero"}'::jsonb),
  ('personal_home', 'trending_movies_day', 'default', true, 20, null, '{"source":"personal","presentation":"rail"}'::jsonb),
  ('personal_home', 'trending_shows_day', 'default', true, 30, null, '{"source":"personal","presentation":"rail"}'::jsonb),
  ('personal_home', 'watchlist_cross_off', 'default', true, 40, null, '{"source":"personal","presentation":"rail"}'::jsonb),
  ('personal_home', 'trakt_recommended_movies', 'default', true, 50, null, '{"source":"personal","presentation":"rail"}'::jsonb),
  ('personal_home', 'trakt_recommended_shows', 'default', true, 60, null, '{"source":"personal","presentation":"rail"}'::jsonb),
  ('personal_home', 'collection', 'default', true, 70, null, '{"source":"personal","presentation":"collection_shelf"}'::jsonb),
  ('personal_home', 'actor_spotlight', 'default', true, 80, null, '{"source":"personal","presentation":"rail"}'::jsonb),
  ('personal_home', 'director_spotlight', 'default', true, 90, null, '{"source":"personal","presentation":"rail"}'::jsonb),
  ('personal_home', 'ai_discover', 'default', true, 100, null, '{"source":"personal","presentation":"rail"}'::jsonb)
on conflict (surface, kind, variant_key) do update
set enabled = excluded.enabled,
    order_index = excluded.order_index,
    item_limit = coalesce(excluded.item_limit, home_feed_surface_sections.item_limit),
    meta = jsonb_strip_nulls(coalesce(home_feed_surface_sections.meta, '{}'::jsonb) || excluded.meta),
    updated_at = timezone('utc'::text, now());

create or replace function public.normalize_personal_home_feed_presentation(
  p_raw text,
  p_kind text default null
)
returns text
language sql
immutable
set search_path to 'public'
as $function$
  with normalized as (
    select
      nullif(lower(btrim(coalesce(p_raw, ''))), '') as raw,
      coalesce(nullif(lower(btrim(coalesce(p_kind, ''))), ''), 'default') as kind
  )
  select case
    when raw in ('hero', 'pill', 'rail', 'collection_shelf') then raw
    when raw in ('poster_rail', 'poster_row') then 'rail'
    when raw in ('collection', 'collection_row') then 'collection_shelf'
    when kind = 'hero_similar' then 'hero'
    when kind = 'collection' then 'collection_shelf'
    else 'rail'
  end
  from normalized;
$function$;

create or replace function public.get_personal_home_feed_section_payload(
  p_profile_id uuid,
  p_kind text,
  p_variant_key text default 'default',
  p_as_of date default current_date,
  p_limit integer default 50
)
returns jsonb
language plpgsql
stable
set search_path to 'public'
as $function$
declare
  v_limit integer := greatest(0, least(coalesce(p_limit, 50), 200));
  v_requested_variant_key text := coalesce(nullif(btrim(p_variant_key), ''), 'default');
  v_effective_variant_key text := v_requested_variant_key;
  v_list jsonb;
  v_meta jsonb := '{}'::jsonb;
  v_source text := 'personal';
  v_presentation text := 'rail';
  v_results jsonb := '[]'::jsonb;
begin
  if not exists (
    select 1
    from public.profiles p
    where p.id = p_profile_id
      and public.is_household_member(p.household_id, auth.uid())
  ) then
    raise exception 'not allowed';
  end if;

  select candidate.list_json,
         candidate.variant_key
    into v_list,
         v_effective_variant_key
  from (
    select
      list_elem as list_json,
      coalesce(nullif(btrim(list_elem->>'variant_key'), ''), 'default') as variant_key,
      list_ord
    from public.profile_recommendations pr
    join lateral jsonb_array_elements(coalesce(pr.lists, '[]'::jsonb)) with ordinality as list_rows(list_elem, list_ord)
      on true
    where pr.profile_id = p_profile_id
      and coalesce(nullif(btrim(list_elem->>'kind'), ''), '') = p_kind
      and (
        coalesce(nullif(btrim(list_elem->>'variant_key'), ''), 'default') = v_requested_variant_key
        or v_requested_variant_key = 'default'
      )
  ) candidate
  order by case when candidate.variant_key = v_requested_variant_key then 0 else 1 end,
           candidate.list_ord
  limit 1;

  v_effective_variant_key := coalesce(v_effective_variant_key, v_requested_variant_key);

  select coalesce(hfss.meta, '{}'::jsonb)
    into v_meta
  from public.home_feed_surface_sections hfss
  where hfss.surface = 'personal_home'
    and hfss.enabled = true
    and hfss.kind = p_kind
    and hfss.variant_key in (v_effective_variant_key, 'default')
  order by case when hfss.variant_key = v_effective_variant_key then 0 else 1 end,
           hfss.order_index,
           hfss.variant_key
  limit 1;

  v_source := coalesce(
    nullif(btrim(coalesce(v_list->>'source', v_meta->>'source', '')), ''),
    'personal'
  );

  v_presentation := public.normalize_personal_home_feed_presentation(
    coalesce(
      nullif(btrim(coalesce(v_list->>'presentation', v_meta->>'presentation', '')), ''),
      null
    ),
    coalesce(nullif(btrim(coalesce(v_list->>'kind', p_kind)), ''), p_kind)
  );

  if v_list is not null then
    select coalesce(jsonb_agg(enriched_item order by item_ord), '[]'::jsonb)
      into v_results
    from (
      select
        item_ord,
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
        end as enriched_item
      from jsonb_array_elements(coalesce(v_list->'results', '[]'::jsonb)) with ordinality as item_rows(item, item_ord)
      left join public.profile_title_state s
        on s.profile_id = p_profile_id
       and s.media_type = item_rows.item->>'media_type'
       and s.tmdb_id = case
         when (item_rows.item->>'id') ~ '^[0-9]+$'
         then (item_rows.item->>'id')::integer
         else null
       end
      where item_ord <= v_limit
    ) enriched;
  end if;

  return jsonb_build_object(
    'kind', p_kind,
    'variant_key', v_effective_variant_key,
    'source', v_source,
    'presentation', v_presentation,
    'as_of', p_as_of,
    'page', 1,
    'total_pages', 1,
    'total_results', jsonb_array_length(v_results),
    'name', coalesce(nullif(btrim(coalesce(v_list->>'name', '')), ''), ''),
    'heading', coalesce(nullif(btrim(coalesce(v_list->>'heading', '')), ''), ''),
    'title', coalesce(nullif(btrim(coalesce(v_list->>'title', '')), ''), ''),
    'subtitle', nullif(btrim(coalesce(v_list->>'subtitle', '')), ''),
    'results', v_results
  );
end;
$function$;

create or replace function public.get_personal_home_feed(
  p_profile_id uuid,
  p_as_of date default current_date,
  p_limit integer default 50
)
returns jsonb
language plpgsql
stable
set search_path to 'public'
as $function$
declare
  v_limit integer := greatest(0, least(coalesce(p_limit, 50), 200));
  v_feed jsonb := '[]'::jsonb;
begin
  if not exists (
    select 1
    from public.profiles p
    where p.id = p_profile_id
      and public.is_household_member(p.household_id, auth.uid())
  ) then
    raise exception 'not allowed';
  end if;

  with raw_lists as (
    select
      list_ord,
      coalesce(nullif(btrim(list_elem->>'kind'), ''), '') as kind,
      coalesce(nullif(btrim(list_elem->>'variant_key'), ''), 'default') as variant_key
    from public.profile_recommendations pr
    join lateral jsonb_array_elements(coalesce(pr.lists, '[]'::jsonb)) with ordinality as list_rows(list_elem, list_ord)
      on true
    where pr.profile_id = p_profile_id
  ),
  deduped as (
    select
      rl.list_ord,
      rl.kind,
      rl.variant_key
    from (
      select
        raw_lists.*,
        row_number() over (
          partition by raw_lists.kind, raw_lists.variant_key
          order by raw_lists.list_ord
        ) as rn
      from raw_lists
      where raw_lists.kind <> ''
    ) rl
    where rl.rn = 1
  ),
  configured as (
    select
      d.list_ord,
      d.kind,
      d.variant_key,
      cfg.order_index,
      least(coalesce(cfg.item_limit, v_limit), v_limit) as item_limit
    from deduped d
    join lateral (
      select
        hfss.order_index,
        hfss.item_limit
      from public.home_feed_surface_sections hfss
      where hfss.surface = 'personal_home'
        and hfss.enabled = true
        and hfss.kind = d.kind
        and hfss.variant_key in (d.variant_key, 'default')
      order by case when hfss.variant_key = d.variant_key then 0 else 1 end,
               hfss.order_index,
               hfss.variant_key
      limit 1
    ) cfg on true
  ),
  sections as (
    select
      c.order_index,
      c.list_ord,
      c.kind,
      c.variant_key,
      public.get_personal_home_feed_section_payload(
        p_profile_id,
        c.kind,
        c.variant_key,
        p_as_of,
        c.item_limit
      ) as section_json
    from configured c
  )
  select coalesce(
    jsonb_agg(section_json order by order_index, list_ord, kind, variant_key),
    '[]'::jsonb
  )
    into v_feed
  from sections
  where jsonb_array_length(section_json->'results') > 0;

  return v_feed;
end;
$function$;

comment on function public.normalize_personal_home_feed_presentation(text, text) is 'Normalizes personal-home presentation tokens while keeping kind semantics separate from UI treatment.';
comment on function public.get_personal_home_feed_section_payload(uuid, text, text, date, integer) is 'Builds one personalized home feed section from the current profile recommendation snapshot using kind plus variant identity.';
comment on function public.get_personal_home_feed(uuid, date, integer) is 'Builds the ordered personal home feed from every configured personalized list, preserving multiple variants per kind.';

revoke all on function public.normalize_personal_home_feed_presentation(text, text) from public;

revoke all on function public.get_personal_home_feed_section_payload(uuid, text, text, date, integer) from public;
revoke all on function public.get_personal_home_feed_section_payload(uuid, text, text, date, integer) from anon;
grant execute on function public.get_personal_home_feed_section_payload(uuid, text, text, date, integer) to authenticated;

revoke all on function public.get_personal_home_feed(uuid, date, integer) from public;
revoke all on function public.get_personal_home_feed(uuid, date, integer) from anon;
grant execute on function public.get_personal_home_feed(uuid, date, integer) to authenticated;

commit;
