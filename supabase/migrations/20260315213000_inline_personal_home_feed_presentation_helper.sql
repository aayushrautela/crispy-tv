begin;

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
  v_raw_presentation text;
  v_kind_for_presentation text := 'default';
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

  v_raw_presentation := nullif(lower(btrim(coalesce(v_list->>'presentation', v_meta->>'presentation', ''))), '');
  v_kind_for_presentation := coalesce(nullif(lower(btrim(coalesce(v_list->>'kind', p_kind))), ''), 'default');

  v_presentation := case
    when v_raw_presentation in ('hero', 'pill', 'rail', 'collection_shelf') then
      v_raw_presentation
    when v_raw_presentation in ('poster_rail', 'poster_row') then
      'rail'
    when v_raw_presentation in ('collection', 'collection_row') then
      'collection_shelf'
    when v_kind_for_presentation = 'hero_similar' then
      'hero'
    when v_kind_for_presentation = 'collection' then
      'collection_shelf'
    else
      'rail'
  end;

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

drop function if exists public.normalize_personal_home_feed_presentation(text, text);

commit;
