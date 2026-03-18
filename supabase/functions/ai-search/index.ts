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

type QueryAnalysis = {
  isRecommendation: boolean;
  anchorHint: string | null;
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

type ResolvedSuggestion = {
  sourceTitle: string;
  item: TmdbItem;
};

const OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
const TMDB_IMAGE_BASE_URL = "https://image.tmdb.org/t/p/";
const FINAL_RESULT_LIMIT = 12;
const RAW_SUGGESTION_LIMIT = 16;
const TITLE_STOP_WORDS = new Set([
  "a",
  "an",
  "and",
  "at",
  "for",
  "from",
  "in",
  "of",
  "on",
  "or",
  "the",
  "to",
  "with",
]);

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
    const analysis = analyzeQuery(query);

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

    const model = configuredOpenRouterModel();

    let suggestedTitles: string[] = [];
    try {
      suggestedTitles = await generateSuggestions({
        analysis,
        query,
        filter,
        locale,
        model,
        openRouterKey,
      });
    } catch (error) {
      console.error("OpenRouter title suggestion failed", error);
      return jsonResponse(502, {
        error: errorMessage(error, "AI search is unavailable right now."),
      });
    }

    const resolvedSuggestions = await resolveSuggestions(suggestedTitles, filter, locale, tmdbApiKey);
    const finalItems = finalizeResolvedItems(resolvedSuggestions, analysis);

    return jsonResponse(200, {
      items: finalItems,
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

function configuredOpenRouterModel(): string {
  return Deno.env.get("AI_SEARCH_OPENROUTER_MODEL")?.trim() || "arcee-ai/trinity-large-preview:free";
}

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error) {
    const message = error.message.trim();
    if (message) {
      return message;
    }
  }
  return fallback;
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

async function generateSuggestions(args: {
  analysis: QueryAnalysis;
  query: string;
  filter: SearchFilter;
  locale: string;
  model: string;
  openRouterKey: string;
}): Promise<string[]> {
  const promptLines = [
    "You help a streaming app answer what-to-watch questions like a smart friend.",
    `User query: ${args.query}`,
    `Catalog scope: ${catalogScopeInstruction(args.filter)}`,
    `Preferred locale: ${args.locale}`,
    "Suggest real released titles only.",
    "Prefer canonical English title names that TMDB is likely to recognize.",
  ];

  if (args.analysis.isRecommendation) {
    promptLines.push("This is a recommendation query, not a direct title lookup.");
    if (args.analysis.anchorHint) {
      promptLines.push(`Anchor phrase: ${args.analysis.anchorHint}`);
    }
    promptLines.push(`Return up to ${RAW_SUGGESTION_LIMIT} genuinely diverse titles.`);
    promptLines.push("Do not include the exact title or closest obvious match the user already asked about.");
    promptLines.push("Include at most one title from the same franchise, collection, series, or shared universe.");
    promptLines.push(
      "Avoid sequels, prequels, spinoffs, reboots, or multiple entries from the same property unless the user explicitly asks for that property.",
    );
    promptLines.push(
      "If you include one franchise-adjacent pick, use the rest of the list for broader nearby recommendations with similar tone, audience, world, genre, or premise.",
    );
  } else {
    promptLines.push("If the query sounds like a direct title lookup, include that title first.");
    promptLines.push(`Return up to ${RAW_SUGGESTION_LIMIT} distinct titles.`);
  }

  promptLines.push("Do not include years, media types, numbering, commentary, or markdown.");
  promptLines.push("Return ONLY a JSON object with this shape:");
  promptLines.push('{"items":["Title One","Title Two"]}');

  const prompt = promptLines.join("\n\n");

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
    throw new Error(extractProviderMessage(errorBody) ?? "OpenRouter request failed.");
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
    throw new Error("AI search returned invalid data.");
  }
  const items = Array.isArray(parsed?.items) ? parsed.items : [];
  const titles: string[] = [];
  const seen = new Set<string>();
  for (const item of items) {
    const title = normalizeSuggestedTitle(item);
    if (!title) {
      continue;
    }
    const key = normalizeTitle(title);
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    titles.push(title);
  }
  return titles;
}

function catalogScopeInstruction(filter: SearchFilter): string {
  switch (filter) {
    case "movies":
      return "Only suggest movies.";
    case "series":
      return "Only suggest TV shows.";
    default:
      return "You may suggest movies or TV shows.";
  }
}

function normalizeSuggestedTitle(value: unknown): string | null {
  const raw = typeof value === "string"
    ? value
    : value && typeof value === "object" && typeof (value as Record<string, unknown>).title === "string"
    ? String((value as Record<string, unknown>).title)
    : "";
  const normalized = raw
    .trim()
    .replace(/^\d+[.)\-:\s]+/, "")
    .replace(/^["']+|["']+$/g, "")
    .trim();
  return normalized || null;
}

async function resolveSuggestions(
  titles: string[],
  filter: SearchFilter,
  locale: string,
  tmdbApiKey: string,
): Promise<ResolvedSuggestion[]> {
  const resolved = await Promise.all(
    titles.map(async (title) => {
      const item = await resolveSuggestion(title, filter, locale, tmdbApiKey);
      return item ? { sourceTitle: title, item } : null;
    }),
  );
  return resolved.filter((item): item is ResolvedSuggestion => item !== null);
}

function finalizeResolvedItems(resolved: ResolvedSuggestion[], analysis: QueryAnalysis): TmdbItem[] {
  const unique = dedupeResolvedSuggestions(resolved);
  if (!analysis.isRecommendation) {
    return unique.map(({ item }) => item).slice(0, FINAL_RESULT_LIMIT);
  }

  const kept: ResolvedSuggestion[] = [];
  let skippedAnchor = false;

  for (const suggestion of unique) {
    if (!skippedAnchor && matchesAnchorSuggestion(suggestion, analysis.anchorHint)) {
      skippedAnchor = true;
      continue;
    }
    if (kept.some((existing) => isSameTitleFamily(existing.item.title, suggestion.item.title))) {
      continue;
    }
    kept.push(suggestion);
    if (kept.length >= FINAL_RESULT_LIMIT) {
      break;
    }
  }

  return kept.map(({ item }) => item);
}

function dedupeResolvedSuggestions(items: ResolvedSuggestion[]): ResolvedSuggestion[] {
  const seen = new Set<string>();
  const result: ResolvedSuggestion[] = [];
  for (const suggestion of items) {
    const key = `${suggestion.item.mediaType}:${suggestion.item.id}`;
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    result.push(suggestion);
  }
  return result;
}

function analyzeQuery(query: string): QueryAnalysis {
  return {
    isRecommendation: isRecommendationQuery(query),
    anchorHint: extractAnchorHint(query),
  };
}

function isRecommendationQuery(query: string): boolean {
  const normalized = normalizeTitle(query);
  if (!normalized) {
    return false;
  }
  return [
    /\blike\b/,
    /\bsimilar\b/,
    /\bother than\b/,
    /\bmore like\b/,
    /\bsomething like\b/,
    /\banything like\b/,
    /\bif i like\b/,
    /\bif i liked\b/,
    /\brecommend\b/,
    /\bwhat should i watch\b/,
    /\bwhat to watch\b/,
  ].some((pattern) => pattern.test(normalized));
}

function extractAnchorHint(query: string): string | null {
  const quoted = [...query.matchAll(/["“”'`](.+?)["“”'`]/g)]
    .map((match) => cleanAnchorHint(match[1]))
    .filter((value): value is string => Boolean(value));
  if (quoted.length > 0) {
    return quoted.sort((left, right) => right.length - left.length)[0] ?? null;
  }

  const patterns = [
    /(?:^|\b)(?:other\s+)?(?:movies?|shows?|series|tv\s+shows?)?\s*(?:like|similar to|more like)\s+(.+)$/i,
    /(?:^|\b)(?:something|anything)\s+like\s+(.+)$/i,
    /(?:^|\b)(?:other than|except)\s+(.+)$/i,
    /(?:^|\b)(?:if i like|if i liked)\s+(.+)$/i,
  ];
  for (const pattern of patterns) {
    const match = query.match(pattern);
    const anchor = cleanAnchorHint(match?.[1] ?? "");
    if (anchor) {
      return anchor;
    }
  }
  return null;
}

function cleanAnchorHint(value: string): string | null {
  const withoutQualifiers = value
    .replace(/[?!.,]+$/g, "")
    .split(/\b(?:but|except|without)\b/i)[0]
    ?.trim() ?? "";
  const cleaned = withoutQualifiers
    .replace(/^(?:movies?|shows?|series|tv\s+shows?)\s+/i, "")
    .trim();
  return cleaned || null;
}

function matchesAnchorSuggestion(suggestion: ResolvedSuggestion, anchorHint: string | null): boolean {
  if (!anchorHint) {
    return false;
  }
  const normalizedAnchor = normalizeTitle(anchorHint);
  if (!normalizedAnchor) {
    return false;
  }
  return titleMatchesAnchor(suggestion.sourceTitle, normalizedAnchor) ||
    titleMatchesAnchor(suggestion.item.title, normalizedAnchor);
}

function titleMatchesAnchor(title: string, normalizedAnchor: string): boolean {
  const normalizedTitle = normalizeTitle(title);
  if (!normalizedTitle) {
    return false;
  }
  if (normalizedTitle === normalizedAnchor) {
    return true;
  }

  const anchorTokens = titleTokens(normalizedAnchor);
  if (anchorTokens.length <= 1) {
    return false;
  }

  const shared = sharedTitleTokenCount(normalizedTitle, normalizedAnchor);
  if (shared >= anchorTokens.length) {
    return true;
  }
  return anchorTokens.length >= 3 &&
    (normalizedTitle.startsWith(normalizedAnchor) || normalizedAnchor.startsWith(normalizedTitle));
}

function isSameTitleFamily(leftTitle: string, rightTitle: string): boolean {
  const leftKey = titleFamilyKey(leftTitle);
  const rightKey = titleFamilyKey(rightTitle);
  return Boolean(leftKey && rightKey && leftKey === rightKey);
}

function titleFamilyKey(title: string): string | null {
  const prefix = title.split(/[:\-]/, 1)[0] ?? title;
  const tokens = titleTokens(normalizeTitle(prefix)).filter((token) => !TITLE_STOP_WORDS.has(token));
  if (tokens.length === 0) {
    return null;
  }
  return tokens.slice(0, Math.min(2, tokens.length)).join(" ");
}

async function resolveSuggestion(
  title: string,
  filter: SearchFilter,
  locale: string,
  tmdbApiKey: string,
): Promise<TmdbItem | null> {
  const params: Record<string, string> = {
    query: title,
    page: "1",
    include_adult: "false",
    language: locale,
  };

  try {
    if (filter === "movies") {
      return selectBestTmdbMatch(await searchTmdb("movie", params, tmdbApiKey), title);
    }
    if (filter === "series") {
      return selectBestTmdbMatch(await searchTmdb("tv", params, tmdbApiKey), title);
    }

    const [movies, series] = await Promise.all([
      searchTmdb("movie", params, tmdbApiKey),
      searchTmdb("tv", params, tmdbApiKey),
    ]);
    return selectBestTmdbMatch([...movies, ...series], title);
  } catch (error) {
    console.error("TMDB suggestion resolution failed", { title, error });
    return null;
  }
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

function selectBestTmdbMatch(items: TmdbItem[], title: string): TmdbItem | null {
  const normalizedTarget = normalizeTitle(title);
  const sorted = [...items].sort((left, right) => {
    const leftScore = scoreTmdbMatch(left, normalizedTarget);
    const rightScore = scoreTmdbMatch(right, normalizedTarget);
    return rightScore - leftScore;
  });
  return sorted[0] ?? null;
}

function scoreTmdbMatch(item: TmdbItem, normalizedTarget: string): number {
  let score = 0;
  const normalizedTitle = normalizeTitle(item.title);
  if (normalizedTitle === normalizedTarget) {
    score += 120;
  } else if (normalizedTitle.startsWith(normalizedTarget) || normalizedTarget.startsWith(normalizedTitle)) {
    score += 80;
  } else if (normalizedTitle.includes(normalizedTarget) || normalizedTarget.includes(normalizedTitle)) {
    score += 40;
  }

  score += Math.min(sharedTitleTokenCount(normalizedTitle, normalizedTarget) * 12, 36);
  if (item.posterUrl) {
    score += 10;
  }
  return score;
}

function sharedTitleTokenCount(left: string, right: string): number {
  const leftTokens = new Set(titleTokens(left));
  let shared = 0;
  for (const token of titleTokens(right)) {
    if (leftTokens.has(token)) {
      shared += 1;
    }
  }
  return shared;
}

function titleTokens(value: string): string[] {
  return value.split(" ").filter((token) => token.length >= 3);
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
