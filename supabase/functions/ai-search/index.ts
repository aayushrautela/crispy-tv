import "jsr:@supabase/functions-js/edge-runtime.d.ts";

import { createClient } from "jsr:@supabase/supabase-js@2";

type SearchFilter = "all" | "movies" | "series";
type CandidateMediaType = "movie" | "tv";

type SearchRequest = {
  query?: unknown;
  filter?: unknown;
  profileId?: unknown;
  locale?: unknown;
};

type SearchCandidate = {
  media_type?: unknown;
  title?: unknown;
  year?: unknown;
};

type TmdbItem = {
  id: number;
  mediaType: CandidateMediaType;
  title: string;
  year: string | null;
  posterUrl: string | null;
  backdropUrl: string | null;
  rating: string | null;
  overview: string | null;
};

const OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
const TMDB_IMAGE_BASE_URL = "https://image.tmdb.org/t/p/";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") {
    return new Response("ok", { headers: CORS_HEADERS });
  }

  try {
    if (request.method !== "POST") {
      return jsonResponse(405, { error: "Method not allowed." });
    }

    const authorization = request.headers.get("Authorization")?.trim() ?? "";
    if (!authorization.toLowerCase().startsWith("bearer ")) {
      return jsonResponse(401, { error: "Missing bearer token." });
    }

    const body = await request.json().catch(() => ({})) as SearchRequest;
    const query = normalizeString(body.query);
    const profileId = normalizeString(body.profileId);
    const filter = normalizeFilter(body.filter);
    const locale = normalizeLocale(body.locale);

    if (!query) {
      return jsonResponse(400, { error: "Query is required." });
    }
    if (!profileId) {
      return jsonResponse(400, { error: "Profile is required." });
    }

    const supabaseUrl = requireEnv("SUPABASE_URL");
    const tmdbApiKey = requireEnv("TMDB_API_KEY");
    const requestApiKey = request.headers.get("apikey")?.trim() ?? "";
    const publishableApiKey = configuredPublicApiKey();

    if (!requestApiKey) {
      return jsonResponse(401, { error: "Missing API key." });
    }
    if (requestApiKey !== publishableApiKey) {
      return jsonResponse(401, { error: "Invalid API key." });
    }

    const token = authorization.replace(/^Bearer\s+/i, "").trim();
    const supabase = createClient(supabaseUrl, requestApiKey, {
      global: {
        headers: {
          apikey: requestApiKey,
          Authorization: authorization,
        },
      },
      auth: {
        persistSession: false,
        autoRefreshToken: false,
        detectSessionInUrl: false,
      },
    });

    const { data: claimsData, error: claimsError } = await supabase.auth.getClaims(token);
    const userId = normalizeString(claimsData?.claims?.sub);
    if (claimsError || !userId) {
      return jsonResponse(401, { error: "Invalid session." });
    }

    const { data: profileData, error: profileError } = await supabase
      .from("profile_data")
      .select("settings")
      .eq("profile_id", profileId)
      .maybeSingle();

    if (profileError) {
      console.error("profile_data lookup failed", profileError);
      return jsonResponse(500, { error: "Unable to load AI settings." });
    }
    if (!profileData) {
      return jsonResponse(404, { error: "Profile not found." });
    }

    const settings = toStringMap(profileData?.settings);
    const openRouterKey = settings["ai.openrouter_key"]?.trim() ?? "";
    if (!openRouterKey) {
      return jsonResponse(412, {
        error: "AI search is not configured for this profile. Add an OpenRouter key in Settings.",
      });
    }

    const model = selectModel(
      settings["ai.insights.model_type"],
      settings["ai.insights.custom_model_name"],
    );

    const seedItems = await fetchSeedItems(query, filter, locale, tmdbApiKey);
    let aiCandidates: Array<{ mediaType: CandidateMediaType; title: string; year: number | null }> = [];
    try {
      aiCandidates = await generateCandidates({
        query,
        filter,
        locale,
        model,
        openRouterKey,
        seedItems,
      });
    } catch (error) {
      console.error("OpenRouter candidate generation failed", error);
    }

    let resolvedItems: TmdbItem[] = [];
    try {
      resolvedItems = await resolveCandidates(aiCandidates, locale, tmdbApiKey);
    } catch (error) {
      console.error("TMDB candidate resolution failed", error);
    }
    const mergedItems = dedupeItems([...resolvedItems, ...seedItems]).slice(0, 12);

    return jsonResponse(200, {
      items: mergedItems,
      fallbackUsed: resolvedItems.length === 0,
    });
  } catch (error) {
    console.error("ai-search failed", error);
    return jsonResponse(500, { error: "AI search is unavailable right now." });
  }
});

function requireEnv(name: string): string {
  const value = Deno.env.get(name)?.trim() ?? "";
  if (!value) {
    throw new Error(`Missing ${name}.`);
  }
  return value;
}

function configuredPublicApiKey(): string {
  return requireEnv("SB_PUBLISHABLE_KEY");
}

function normalizeString(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function normalizeFilter(value: unknown): SearchFilter {
  const normalized = normalizeString(value).toLowerCase();
  switch (normalized) {
    case "movies":
      return "movies";
    case "series":
      return "series";
    default:
      return "all";
  }
}

function normalizeLocale(value: unknown): string {
  const normalized = normalizeString(value);
  return /^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,3}$/.test(normalized) ? normalized : "en-US";
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      ...CORS_HEADERS,
      "Content-Type": "application/json",
    },
  });
}

function toStringMap(value: unknown): Record<string, string> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return {};
  }
  const entries = Object.entries(value as Record<string, unknown>);
  const result: Record<string, string> = {};
  for (const [key, raw] of entries) {
    if (typeof raw === "string") {
      result[key] = raw;
    }
  }
  return result;
}

function selectModel(rawType: string | undefined, rawCustomModel: string | undefined): string {
  const modelType = (rawType ?? "").trim().toLowerCase();
  const customModel = (rawCustomModel ?? "").trim();
  switch (modelType) {
    case "nvidia-nemotron":
      return "nvidia/nemotron-3-nano-30b-a3b:free";
    case "custom":
      return customModel || "deepseek/deepseek-r1-0528:free";
    case "deepseek-r1":
    default:
      return "deepseek/deepseek-r1-0528:free";
  }
}

async function fetchSeedItems(
  query: string,
  filter: SearchFilter,
  locale: string,
  tmdbApiKey: string,
): Promise<TmdbItem[]> {
  const params = {
    query,
    page: "1",
    include_adult: "false",
    language: locale,
  };

  if (filter === "movies") {
    return searchTmdb("movie", params, tmdbApiKey);
  }
  if (filter === "series") {
    return searchTmdb("tv", params, tmdbApiKey);
  }

  const [movies, series] = await Promise.all([
    searchTmdb("movie", params, tmdbApiKey),
    searchTmdb("tv", params, tmdbApiKey),
  ]);
  return interleave(movies, series);
}

async function generateCandidates(args: {
  query: string;
  filter: SearchFilter;
  locale: string;
  model: string;
  openRouterKey: string;
  seedItems: TmdbItem[];
}): Promise<Array<{ mediaType: CandidateMediaType; title: string; year: number | null }>> {
  const seedLines = args.seedItems
    .slice(0, 8)
    .map((item, index) => `${index + 1}. ${item.mediaType}|${item.title}|${item.year ?? "unknown"}`)
    .join("\n");

  const prompt = [
    "You help a streaming app turn natural-language search into concrete TMDB titles.",
    `User query: ${args.query}`,
    `Requested filter: ${args.filter}`,
    `Locale: ${args.locale}`,
    "If the query looks like an exact title search, keep the exact title first.",
    "If the query is descriptive, suggest the strongest matching real titles.",
    "Allowed media_type values: movie, tv.",
    "Return at most 8 items.",
    "Return ONLY a JSON object with this shape:",
    '{"items":[{"media_type":"movie","title":"Title","year":2024}]}',
    "TMDB seed matches:",
    seedLines || "No direct TMDB seed matches.",
  ].join("\n\n");

  const response = await fetch(OPENROUTER_URL, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${args.openRouterKey}`,
      "HTTP-Referer": "https://crispy-app.com",
      "X-Title": "Crispy Rewrite",
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify({
      model: args.model,
      messages: [
        {
          role: "system",
          content:
            "Return compact, valid JSON only. Never include markdown fences. Suggest real movie or TV titles only.",
        },
        {
          role: "user",
          content: prompt,
        },
      ],
      response_format: { type: "json_object" },
    }),
  });

  if (!response.ok) {
    const errorBody = await response.text();
    console.error("OpenRouter request failed", response.status, errorBody);
    throw new Error("OpenRouter request failed.");
  }

  const payload = await response.json().catch(() => ({}));
  const content = payload?.choices?.[0]?.message?.content;
  if (typeof content !== "string" || !content.trim()) {
    return [];
  }

  let parsed: Record<string, unknown>;
  try {
    parsed = JSON.parse(extractJsonObject(content)) as Record<string, unknown>;
  } catch (error) {
    console.error("OpenRouter returned invalid JSON", error, content);
    return [];
  }
  const items = Array.isArray(parsed?.items) ? parsed.items : [];
  return items
    .map((item: SearchCandidate) => normalizeCandidate(item, args.filter))
    .filter((item): item is { mediaType: CandidateMediaType; title: string; year: number | null } => item !== null);
}

function normalizeCandidate(
  candidate: SearchCandidate,
  requestedFilter: SearchFilter,
): { mediaType: CandidateMediaType; title: string; year: number | null } | null {
  const title = typeof candidate.title === "string" ? candidate.title.trim() : "";
  if (!title) {
    return null;
  }

  const rawMediaType = typeof candidate.media_type === "string"
    ? candidate.media_type.trim().toLowerCase()
    : "";

  const mediaType = requestedFilter === "movies"
    ? "movie"
    : requestedFilter === "series"
    ? "tv"
    : rawMediaType === "tv"
    ? "tv"
    : "movie";

  const rawYear = typeof candidate.year === "number"
    ? candidate.year
    : typeof candidate.year === "string"
    ? Number.parseInt(candidate.year, 10)
    : Number.NaN;
  const year = Number.isInteger(rawYear) && rawYear >= 1800 && rawYear <= 3000 ? rawYear : null;

  return { mediaType, title, year };
}

async function resolveCandidates(
  candidates: Array<{ mediaType: CandidateMediaType; title: string; year: number | null }>,
  locale: string,
  tmdbApiKey: string,
): Promise<TmdbItem[]> {
  const resolved: TmdbItem[] = [];
  for (const candidate of candidates) {
    const params: Record<string, string> = {
      query: candidate.title,
      page: "1",
      include_adult: "false",
      language: locale,
    };
    if (candidate.year != null) {
      params[candidate.mediaType === "movie" ? "year" : "first_air_date_year"] = String(candidate.year);
    }

    const results = await searchTmdb(candidate.mediaType, params, tmdbApiKey);
    const best = selectBestTmdbMatch(results, candidate.title, candidate.year);
    if (best) {
      resolved.push(best);
    }
  }
  return resolved;
}

async function searchTmdb(
  mediaType: CandidateMediaType,
  params: Record<string, string>,
  tmdbApiKey: string,
): Promise<TmdbItem[]> {
  const url = new URL(`https://api.themoviedb.org/3/search/${mediaType}`);
  url.searchParams.set("api_key", tmdbApiKey);
  for (const [key, value] of Object.entries(params)) {
    url.searchParams.set(key, value);
  }

  const response = await fetch(url.toString(), {
    headers: {
      Accept: "application/json",
    },
  });
  if (!response.ok) {
    throw new Error(`TMDB search failed with HTTP ${response.status}.`);
  }

  const payload = await response.json().catch(() => ({}));
  const results = Array.isArray(payload?.results) ? payload.results : [];
  return results
    .map((item: Record<string, unknown>) => toTmdbItem(item, mediaType))
    .filter((item): item is TmdbItem => item !== null);
}

function toTmdbItem(item: Record<string, unknown>, mediaType: CandidateMediaType): TmdbItem | null {
  const id = typeof item.id === "number" ? item.id : 0;
  if (!Number.isInteger(id) || id <= 0) {
    return null;
  }

  const rawTitle = mediaType === "movie" ? item.title : item.name;
  const title = typeof rawTitle === "string" ? rawTitle.trim() : "";
  if (!title) {
    return null;
  }

  const rawDate = mediaType === "movie" ? item.release_date : item.first_air_date;
  const year = typeof rawDate === "string" && rawDate.length >= 4 ? rawDate.slice(0, 4) : null;
  const rating = typeof item.vote_average === "number" && Number.isFinite(item.vote_average)
    ? item.vote_average.toFixed(1)
    : null;

  return {
    id,
    mediaType,
    title,
    year,
    posterUrl: tmdbImageUrl(typeof item.poster_path === "string" ? item.poster_path : null, "w500"),
    backdropUrl: tmdbImageUrl(typeof item.backdrop_path === "string" ? item.backdrop_path : null, "w780"),
    rating,
    overview: typeof item.overview === "string" ? item.overview.trim() || null : null,
  };
}

function selectBestTmdbMatch(items: TmdbItem[], title: string, year: number | null): TmdbItem | null {
  const normalizedTarget = normalizeTitle(title);
  const sorted = [...items].sort((left, right) => {
    const leftScore = scoreTmdbMatch(left, normalizedTarget, year);
    const rightScore = scoreTmdbMatch(right, normalizedTarget, year);
    return rightScore - leftScore;
  });
  return sorted[0] ?? null;
}

function scoreTmdbMatch(item: TmdbItem, normalizedTarget: string, year: number | null): number {
  let score = 0;
  const normalizedTitle = normalizeTitle(item.title);
  if (normalizedTitle === normalizedTarget) {
    score += 100;
  } else if (normalizedTitle.startsWith(normalizedTarget) || normalizedTarget.startsWith(normalizedTitle)) {
    score += 60;
  }

  if (year != null && item.year === String(year)) {
    score += 40;
  }
  if (item.posterUrl) {
    score += 10;
  }
  return score;
}

function normalizeTitle(value: string): string {
  return value
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function tmdbImageUrl(path: string | null, size: string): string | null {
  if (!path) {
    return null;
  }
  return `${TMDB_IMAGE_BASE_URL}${size}${path.startsWith("/") ? path : `/${path}`}`;
}

function dedupeItems(items: TmdbItem[]): TmdbItem[] {
  const seen = new Set<string>();
  const result: TmdbItem[] = [];
  for (const item of items) {
    const key = `${item.mediaType}:${item.id}`;
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    result.push(item);
  }
  return result;
}

function interleave<T>(first: T[], second: T[]): T[] {
  const output: T[] = [];
  const maxLength = Math.max(first.length, second.length);
  for (let index = 0; index < maxLength; index += 1) {
    if (index < first.length) {
      output.push(first[index]);
    }
    if (index < second.length) {
      output.push(second[index]);
    }
  }
  return output;
}

function extractJsonObject(text: string): string {
  const trimmed = text.trim();
  const unfenced = trimmed
    .replace(/^```json\s*/i, "")
    .replace(/^```\s*/i, "")
    .replace(/```$/i, "")
    .trim();
  const start = unfenced.indexOf("{");
  const end = unfenced.lastIndexOf("}");
  return start >= 0 && end > start ? unfenced.slice(start, end + 1) : unfenced;
}

function extractProviderMessage(rawBody: string): string | null {
  const trimmed = rawBody.trim();
  if (!trimmed) {
    return null;
  }
  try {
    const parsed = JSON.parse(trimmed);
    if (typeof parsed?.error?.message === "string" && parsed.error.message.trim()) {
      return parsed.error.message.trim();
    }
    if (typeof parsed?.message === "string" && parsed.message.trim()) {
      return parsed.message.trim();
    }
  } catch {
    return trimmed;
  }
  return trimmed;
}
