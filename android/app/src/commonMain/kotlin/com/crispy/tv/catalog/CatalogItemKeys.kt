package com.crispy.tv.catalog

/**
 * The identity a [CatalogItem] is known by everywhere in the app.
 *
 * `type` is part of it because `itemId` alone is not unique across the shapes a
 * title can take: the same backend row arrives from the `movie` and the `series`
 * endpoint, and `toCatalogItem()` reads the type off the row rather than off the
 * request. `DetailsRoute` builds its ViewModel key the same way and says why.
 *
 * **This exists because the same string was written out by hand in five places,
 * and one of them was the site of a production crash.** A `LazyVerticalGrid` whose
 * `key` is this string threw
 * `IllegalArgumentException: Key "show:2863a087c6634b668d337e5ac4a300b2" was already used`
 * because a title that appeared on two pages of the same pager was appended
 * twice. The two halves of that fix are [lazyKey] and
 * [MutableSet.acceptFirstCatalogItems], and the second is the one that matters:
 * a unique key would only have stopped the crash while the user still saw the
 * same title twice.
 */
internal fun CatalogItem.lazyKey(): String = "$type:$id"

/**
 * Keeps only the items whose [lazyKey] has not been accepted before, and
 * remembers every key it accepts for the pages that follow.
 *
 * Both are needed and neither is enough alone. Within one page, a title the
 * backend returned from both the `movie` and the `series` endpoint is already
 * duplicated — the `all` filter fans out to two requests and the merge
 * concatenates them. Across pages, `sort` is re-evaluated by the server on every
 * request, so a `popularity` list re-ordered between page 1 and page 2 hands
 * back a title the user is already looking at.
 *
 * The state is per-PagingSource instance on purpose: `Pager` builds a new source
 * from its factory for every generation, so a refresh starts from an empty set
 * without anyone having to clear it. A page may come back with nothing left in it
 * and a non-null `nextKey`, which is the documented filter-a-PagingSource
 * pattern — paging keeps loading until a page contributes something or the keys
 * run out.
 */
internal fun MutableSet<String>.acceptFirstCatalogItems(items: List<CatalogItem>): List<CatalogItem> =
    items.filter { add(it.lazyKey()) }