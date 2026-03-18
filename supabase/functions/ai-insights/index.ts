import "jsr:@supabase/functions-js/edge-runtime.d.ts";

import { createClient } from "jsr:@supabase/supabase-js@2";

type InsightsRequest = {
  tmdbId?: unknown;
  mediaType?: unknown;
  profileId?: unknown;
  locale?: unknown;
};

type MediaType = "movie" | "tv";

type CacheRow = {
  payload: unknown;
};

type ProfileRow = {
  household_id: string;
};

type ProfileDataRow = {
  settings: unknown;
};

type TmdbTitleContext = {
  tmdbId: number;
  mediaType: MediaType;
  title: string;
  year: string | null;
  description: string | null;
  rating: string | null;
  genres: string[];
  reviews: Array<{
    author: string;
    rating: number | null;
    content: string;
  }>;
};

const OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
const GENERATION_VERSION = "v1";
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

    const requestApiKey = request.headers.get("apikey")?.trim() ?? "";
    const publishableApiKey = configuredPublicApiKey();
    if (!requestApiKey) {
      return jsonResponse(401, { error: "Missing API key." });
    }
    if (requestApiKey !== publishableApiKey) {
      return jsonResponse(401, { error: "Invalid API key." });
    }

    const body = await request.json().catch(() => ({})) as InsightsRequest;
    const tmdbId = normalizePositiveInt(body.tmdbId);
    const mediaType = normalizeMediaType(body.mediaType);
    const profileId = normalizeString(body.profileId);
    const locale = normalizeLocale(body.locale);

    if (!tmdbId) {
      return jsonResponse(400, { error: "TMDB id is required." });
    }
    if (!mediaType) {
      return jsonResponse(400, { error: "Media type must be movie or tv." });
    }
    if (!profileId) {
      return jsonResponse(400, { error: "Profile is required." });
    }

    const supabaseUrl = requireEnv("SUPABASE_URL");
    const tmdbApiKey = requireEnv("TMDB_API_KEY");
    const adminApiKey = requireFirstEnv("SB_SECRET_KEY", "SUPABASE_SERVICE_ROLE_KEY");
    const requestClient = createRequestClient(supabaseUrl, requestApiKey, authorization);
    const userId = await resolveUserId(requestClient, authorization);
    if (!userId) {
      return jsonResponse(401, { error: "Invalid session." });
    }

    const adminClient = createAdminClient(supabaseUrl, adminApiKey);

    const access = await resolveProfileAccess(adminClient, userId, profileId);
    if (!access.ok) {
      return jsonResponse(access.status, { error: access.error });
    }

    const profileSettings = await loadProfileSettings(requestClient, profileId);
    if (!profileSettings.ok) {
      return jsonResponse(profileSettings.status, { error: profileSettings.error });
    }

    const openRouterKey = profileSettings.openRouterKey;
    if (!openRouterKey) {
      return jsonResponse(412, {
        error: "AI insights are not configured for this profile. Add an OpenRouter key in Settings.",
      });
    }

    const cached = await loadCachedInsights(adminClient, tmdbId, mediaType, locale);
    if (cached) {
      return jsonResponse(200, cached);
    }

    const titleContext = await loadTmdbTitleContext(tmdbId, mediaType, locale, tmdbApiKey);
    if (!titleContext) {
      return jsonResponse(404, { error: "Unable to load title data for AI insights." });
    }

    const model = configuredInsightsModel();

    let payload: Record<string, unknown>;
    try {
      payload = await generateInsightsPayload(titleContext, openRouterKey, model);
    } catch (error) {
      console.error("AI insights generation failed", error);
      return jsonResponse(502, {
        error: errorMessage(error, "AI insights are unavailable right now."),
      });
    }

    const saved = await saveCachedInsights(adminClient, {
      tmdbId,
      mediaType,
      locale,
      payload,
      profileId,
      modelName: model,
    });

    return jsonResponse(200, saved);
  } catch (error) {
    console.error("ai-insights failed", error);
    return jsonResponse(500, { error: "AI insights are unavailable right now." });
  }
});

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      ...CORS_HEADERS,
      "Content-Type": "application/json",
    },
  });
}

function requireEnv(name: string): string {
  const value = Deno.env.get(name)?.trim() ?? "";
  if (!value) {
    throw new Error(`Missing ${name}.`);
  }
  return value;
}

function requireFirstEnv(...names: string[]): string {
  for (const name of names) {
    const value = Deno.env.get(name)?.trim() ?? "";
    if (value) {
      return value;
    }
  }
  throw new Error(`Missing one of: ${names.join(", ")}.`);
}

function configuredPublicApiKey(): string {
  return requireEnv("SB_PUBLISHABLE_KEY");
}

function configuredInsightsModel(): string {
  return requireEnv("AI_INSIGHTS_OPENROUTER_MODEL");
}

function normalizeString(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function normalizePositiveInt(value: unknown): number | null {
  const parsed = typeof value === "number" ? value : Number.parseInt(normalizeString(value), 10);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}

function normalizeMediaType(value: unknown): MediaType | null {
  const normalized = normalizeString(value).toLowerCase();
  return normalized === "movie" || normalized === "tv" ? normalized : null;
}

function normalizeLocale(value: unknown): string {
  const normalized = normalizeString(value);
  return /^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,3}$/.test(normalized) ? normalized : "en-US";
}

function createRequestClient(supabaseUrl: string, requestApiKey: string, authorization: string) {
  return createClient(supabaseUrl, requestApiKey, {
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
}

function createAdminClient(supabaseUrl: string, adminApiKey: string) {
  return createClient(supabaseUrl, adminApiKey, {
    auth: {
      persistSession: false,
      autoRefreshToken: false,
      detectSessionInUrl: false,
    },
  });
}

async function resolveUserId(requestClient: ReturnType<typeof createRequestClient>, authorization: string): Promise<string | null> {
  const token = authorization.replace(/^Bearer\s+/i, "").trim();
  const { data, error } = await requestClient.auth.getClaims(token);
  if (error) {
    return null;
  }
  return typeof data?.claims?.sub === "string" && data.claims.sub.trim() ? data.claims.sub.trim() : null;
}

async function resolveProfileAccess(
  adminClient: ReturnType<typeof createAdminClient>,
  userId: string,
  profileId: string,
): Promise<{ ok: true } | { ok: false; status: number; error: string }> {
  const membershipResult = await adminClient
    .from("household_members")
    .select("household_id")
    .eq("user_id", userId)
    .maybeSingle();
  const membership = membershipResult.data as ProfileRow | null;
  if (membershipResult.error) {
    console.error("Failed to resolve household membership", membershipResult.error);
    return { ok: false, status: 500, error: "Failed to resolve household membership." };
  }
  if (!membership?.household_id) {
    return { ok: false, status: 403, error: "Profile access is not allowed." };
  }

  const profileResult = await adminClient
    .from("profiles")
    .select("household_id")
    .eq("id", profileId)
    .maybeSingle();
  const profile = profileResult.data as ProfileRow | null;
  if (profileResult.error) {
    console.error("Failed to resolve profile access", profileResult.error);
    return { ok: false, status: 500, error: "Failed to resolve profile access." };
  }
  if (!profile?.household_id) {
    return { ok: false, status: 404, error: "Profile not found." };
  }
  if (profile.household_id !== membership.household_id) {
    return { ok: false, status: 403, error: "Profile access is not allowed." };
  }

  return { ok: true };
}

async function loadProfileSettings(
  requestClient: ReturnType<typeof createRequestClient>,
  profileId: string,
): Promise<
  | { ok: true; openRouterKey: string }
  | { ok: false; status: number; error: string }
> {
  const result = await requestClient
    .from("profile_data")
    .select("settings")
    .eq("profile_id", profileId)
    .maybeSingle();
  const row = result.data as ProfileDataRow | null;

  if (result.error) {
    console.error("profile_data lookup failed", result.error);
    return { ok: false, status: 500, error: "Unable to load AI settings." };
  }
  if (!row) {
    return { ok: false, status: 404, error: "Profile not found." };
  }

  const settings = toStringMap(row.settings);
  return { ok: true, openRouterKey: settings["ai.openrouter_key"]?.trim() ?? "" };
}

function toStringMap(value: unknown): Record<string, string> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return {};
  }
  const result: Record<string, string> = {};
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    if (typeof raw === "string") {
      result[key] = raw;
    }
  }
  return result;
}

async function loadCachedInsights(
  adminClient: ReturnType<typeof createAdminClient>,
  tmdbId: number,
  mediaType: MediaType,
  locale: string,
): Promise<Record<string, unknown> | null> {
  const result = await adminClient
    .from("ai_insights_cache")
    .select("payload")
    .eq("tmdb_id", tmdbId)
    .eq("media_type", mediaType)
    .eq("locale", locale)
    .eq("generation_version", GENERATION_VERSION)
    .maybeSingle();

  const row = result.data as CacheRow | null;
  if (result.error) {
    console.error("Failed to load cached AI insights", result.error);
    return null;
  }
  if (!row || !row.payload || typeof row.payload !== "object" || Array.isArray(row.payload)) {
    return null;
  }
  return row.payload as Record<string, unknown>;
}

async function saveCachedInsights(
  adminClient: ReturnType<typeof createAdminClient>,
  args: {
    tmdbId: number;
    mediaType: MediaType;
    locale: string;
    payload: Record<string, unknown>;
    profileId: string;
    modelName: string;
  },
): Promise<Record<string, unknown>> {
  const result = await adminClient
    .from("ai_insights_cache")
    .upsert({
      tmdb_id: args.tmdbId,
      media_type: args.mediaType,
      locale: args.locale,
      generation_version: GENERATION_VERSION,
      model_name: args.modelName,
      payload: args.payload,
      generated_by_profile_id: args.profileId,
    }, {
      onConflict: "tmdb_id,media_type,locale,generation_version",
    })
    .select("payload")
    .maybeSingle();

  const row = result.data as CacheRow | null;
  if (result.error) {
    console.error("Failed to persist AI insights cache", result.error);
    return args.payload;
  }
  if (!row?.payload || typeof row.payload !== "object" || Array.isArray(row.payload)) {
    return args.payload;
  }
  return row.payload as Record<string, unknown>;
}

async function loadTmdbTitleContext(
  tmdbId: number,
  mediaType: MediaType,
  locale: string,
  tmdbApiKey: string,
): Promise<TmdbTitleContext | null> {
  const endpoint = mediaType === "movie" ? "movie" : "tv";
  const url = new URL(`https://api.themoviedb.org/3/${endpoint}/${tmdbId}`);
  url.searchParams.set("api_key", tmdbApiKey);
  url.searchParams.set("language", locale);
  url.searchParams.set("append_to_response", "reviews");

  const response = await fetch(url.toString(), {
    headers: {
      Accept: "application/json",
    },
  });

  if (!response.ok) {
    console.error("TMDB title lookup failed", response.status, await response.text());
    return null;
  }

  const payload = await response.json().catch(() => ({}));
  const title = typeof payload?.title === "string"
    ? payload.title.trim()
    : typeof payload?.name === "string"
    ? payload.name.trim()
    : "";
  if (!title) {
    return null;
  }

  const rawDate = mediaType === "movie" ? payload?.release_date : payload?.first_air_date;
  const year = typeof rawDate === "string" && rawDate.length >= 4 ? rawDate.slice(0, 4) : null;
  const rating = typeof payload?.vote_average === "number" && Number.isFinite(payload.vote_average)
    ? payload.vote_average.toFixed(1)
    : null;
  const genres = Array.isArray(payload?.genres)
    ? payload.genres
      .map((genre: Record<string, unknown>) => typeof genre?.name === "string" ? genre.name.trim() : "")
      .filter((name: string) => Boolean(name))
    : [];
  const reviews = Array.isArray(payload?.reviews?.results)
    ? payload.reviews.results
      .map((review: Record<string, unknown>) => ({
        author: typeof review?.author === "string" ? review.author.trim() : "",
        rating: typeof review?.author_details?.rating === "number" ? review.author_details.rating : null,
        content: typeof review?.content === "string" ? review.content.trim() : "",
      }))
      .filter((review) => review.content)
      .slice(0, 10)
    : [];

  return {
    tmdbId,
    mediaType,
    title,
    year,
    description: typeof payload?.overview === "string" ? payload.overview.trim() || null : null,
    rating,
    genres,
    reviews,
  };
}

async function generateInsightsPayload(
  context: TmdbTitleContext,
  openRouterKey: string,
  model: string,
): Promise<Record<string, unknown>> {
  const response = await fetch(OPENROUTER_URL, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${openRouterKey}`,
      "HTTP-Referer": "https://crispy-app.com",
      "X-Title": "Crispy Rewrite",
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify({
      model,
      messages: [
        {
          role: "user",
          content: buildPrompt(context),
        },
      ],
      response_format: { type: "json_object" },
    }),
  });

  if (!response.ok) {
    const errorBody = await response.text();
    console.error("OpenRouter AI insights request failed", response.status, errorBody);
    throw new Error(extractProviderMessage(errorBody) ?? "OpenRouter request failed.");
  }

  const payload = await response.json().catch(() => ({}));
  const content = payload?.choices?.[0]?.message?.content;
  if (typeof content !== "string" || !content.trim()) {
    throw new Error("AI insights returned empty data.");
  }

  const parsed = JSON.parse(extractJsonObject(content)) as Record<string, unknown>;
  const normalized = normalizeInsightsPayload(parsed);
  if (!normalized) {
    throw new Error("AI insights returned invalid data.");
  }
  return normalized;
}

function buildPrompt(context: TmdbTitleContext): string {
  const plot = context.description?.trim() || "N/A";
  const rating = context.rating?.trim() || "N/A";
  const genres = context.genres.join(", ") || "N/A";
  const formattedReviews = context.reviews.length === 0
    ? "No user reviews available."
    : context.reviews
      .map((review) => {
        const author = review.author || "Unknown";
        const authorRating = review.rating == null ? "N/A" : String(review.rating);
        const content = review.content
          .replace(/\n+/g, " ")
          .replace(/\s+/g, " ")
          .trim()
          .slice(0, 500);
        return `(Author: ${author}, Rating: ${authorRating}) "${content}"`;
      })
      .join("\n---\n");

  return [
    "Be a film enthusiast, not a critic. Use simple, conversational, and exciting English.",
    "Avoid complex words, academic jargon, or flowery prose. Write like you're talking to a friend.",
    "Do NOT use generic headings.",
    "Context:",
    `Title: ${context.title} (${context.year ?? "N/A"})`,
    `Plot: ${plot}`,
    `Rating: ${rating}`,
    `Genres: ${genres}`,
    "User Reviews:",
    formattedReviews,
    "Task:",
    "Generate a JSON object with:",
    "- insights: an array of 3 objects. Each object must include:",
    '  - category: a short uppercase label (e.g. CONSENSUS, VIBE, STYLE)',
    "  - title: a punchy, short headline",
    "  - content: 2-3 sentences",
    '  - type: one of ["consensus","performance","theme","vibe","style","controversy","character"]',
    "- trivia: one \"Did you know?\" fact (1-2 sentences)",
    "Return ONLY valid JSON.",
  ].join("\n\n");
}

function normalizeInsightsPayload(payload: Record<string, unknown>): Record<string, unknown> | null {
  const trivia = typeof payload.trivia === "string" ? payload.trivia.trim() : "";
  const items = Array.isArray(payload.insights) ? payload.insights : [];
  const insights = items
    .map((item) => normalizeInsightCard(item))
    .filter((item): item is Record<string, string> => item !== null)
    .slice(0, 3);

  if (insights.length === 0) {
    return null;
  }

  return {
    insights,
    trivia,
  };
}

function normalizeInsightCard(value: unknown): Record<string, string> | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return null;
  }
  const item = value as Record<string, unknown>;
  const type = typeof item.type === "string" ? item.type.trim() : "";
  const title = typeof item.title === "string" ? item.title.trim() : "";
  const category = typeof item.category === "string" ? item.category.trim() : "";
  const content = typeof item.content === "string" ? item.content.trim() : "";
  if (!type || !title || !category || !content) {
    return null;
  }
  return { type, title, category, content };
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

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error) {
    const message = error.message.trim();
    if (message) {
      return message;
    }
  }
  return fallback;
}
