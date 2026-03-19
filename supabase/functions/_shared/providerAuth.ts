import { createClient } from 'jsr:@supabase/supabase-js@2';

export const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

export type ProviderName = 'trakt' | 'simkl';

export type ProviderSessionRow = {
  access_token: string;
  access_token_expires_at: string | null;
  provider_username: string | null;
  provider_user_id: string | null;
  connected_at: string | null;
};

export type PersistProviderSessionInput = {
  profileId: string;
  provider: ProviderName;
  accessToken: string;
  accessTokenExpiresAt: string | null;
  providerUserId: string | null;
  providerUsername: string | null;
  refreshToken: string | null;
  connectedAt: string;
  lastRefreshAt: string;
  lastRefreshError: string | null;
};

type AuthContext = {
  userId: string;
  adminClient: ReturnType<typeof createClient>;
};

export function jsonResponse(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      ...corsHeaders,
      'Content-Type': 'application/json',
    },
  });
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function firstRow<T>(value: unknown): T | null {
  return Array.isArray(value) && value.length > 0 ? value[0] as T : null;
}

export function requireEnv(name: string): string {
  const value = Deno.env.get(name)?.trim() ?? '';
  if (!value) {
    throw new Error(`Missing ${name}.`);
  }
  return value;
}

export function requireFirstEnv(...names: string[]): string {
  for (const name of names) {
    const value = Deno.env.get(name)?.trim() ?? '';
    if (value) {
      return value;
    }
  }
  throw new Error(`Missing one of: ${names.join(', ')}.`);
}

export function configuredPublicApiKey(): string {
  return requireEnv('SB_PUBLISHABLE_KEY');
}

export function normalizeProvider(raw: unknown): ProviderName | null {
  const value = typeof raw === 'string' ? raw.trim().toLowerCase() : '';
  if (value === 'trakt' || value === 'simkl') {
    return value;
  }
  return null;
}

export function expiresAtIsoFromNow(expiresIn: unknown): string | null {
  const seconds = typeof expiresIn === 'number'
    ? expiresIn
    : typeof expiresIn === 'string'
      ? Number(expiresIn)
      : Number.NaN;
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return null;
  }
  return new Date(Date.now() + seconds * 1000).toISOString();
}

export async function authenticateRequest(req: Request): Promise<AuthContext | Response> {
  const supabaseUrl = requireEnv('SUPABASE_URL');
  const adminApiKey = requireFirstEnv('SB_SECRET_KEY', 'SUPABASE_SERVICE_ROLE_KEY');
  const requestApiKey = req.headers.get('apikey')?.trim() ?? '';
  const authorization = req.headers.get('Authorization')?.trim() ?? '';
  const publishableApiKey = configuredPublicApiKey();

  if (!requestApiKey) {
    return jsonResponse(401, { error: 'Missing API key.' });
  }
  if (requestApiKey !== publishableApiKey) {
    return jsonResponse(401, { error: 'Invalid API key.' });
  }
  if (!authorization.toLowerCase().startsWith('bearer ')) {
    return jsonResponse(401, { error: 'Missing bearer token.' });
  }

  const userId = await resolveUserId(supabaseUrl, requestApiKey, authorization);
  if (!userId) {
    return jsonResponse(401, { error: 'Authentication required.' });
  }

  return {
    userId,
    adminClient: createAdminClient(supabaseUrl, adminApiKey),
  };
}

export async function authorizeProfileAccess(
  adminClient: ReturnType<typeof createClient>,
  profileId: string,
  userId: string,
): Promise<boolean> {
  const profileResult = await adminClient
    .from('profiles')
    .select('household_id')
    .eq('id', profileId)
    .limit(1)
    .maybeSingle();

  const householdId = profileResult.data?.household_id;
  if (profileResult.error || typeof householdId !== 'string' || !householdId.trim()) {
    return false;
  }

  const membershipResult = await adminClient
    .from('household_members')
    .select('user_id')
    .eq('household_id', householdId)
    .eq('user_id', userId)
    .limit(1)
    .maybeSingle();

  return !membershipResult.error && !!membershipResult.data;
}

export async function loadConnectedAt(
  adminClient: ReturnType<typeof createClient>,
  profileId: string,
  provider: ProviderName,
): Promise<string | null> {
  const providerAccountResult = await adminClient
    .from('provider_accounts')
    .select('connected_at')
    .eq('profile_id', profileId)
    .eq('provider', provider)
    .limit(1);

  if (providerAccountResult.error) {
    console.error('Failed to load provider account', providerAccountResult.error);
    throw new Error('Failed to load provider account.');
  }

  const providerAccount = firstRow<{ connected_at: string | null }>(providerAccountResult.data);
  return providerAccount?.connected_at ?? null;
}

export async function upsertProviderSession(
  adminClient: ReturnType<typeof createClient>,
  input: PersistProviderSessionInput,
): Promise<ProviderSessionRow> {
  const publicUpsert = await adminClient
    .from('provider_accounts')
    .upsert({
      profile_id: input.profileId,
      provider: input.provider,
      access_token: input.accessToken,
      access_token_expires_at: input.accessTokenExpiresAt,
      provider_user_id: input.providerUserId,
      provider_username: input.providerUsername,
      connected_at: input.connectedAt,
      last_refresh_at: input.lastRefreshAt,
      last_refresh_error: input.lastRefreshError,
    }, { onConflict: 'profile_id,provider' })
    .select('access_token, access_token_expires_at, provider_username, provider_user_id, connected_at')
    .single();

  if (publicUpsert.error) {
    console.error('Failed to upsert public provider account', publicUpsert.error);
    throw new Error('Failed to persist provider account.');
  }

  const privateUpsert = await adminClient
    .schema('private')
    .from('provider_credentials')
    .upsert({
      profile_id: input.profileId,
      provider: input.provider,
      refresh_token: input.refreshToken,
      access_token: input.accessToken,
      access_token_expires_at: input.accessTokenExpiresAt,
      provider_user_id: input.providerUserId,
      provider_username: input.providerUsername,
      last_refresh_at: input.lastRefreshAt,
      last_refresh_error: input.lastRefreshError,
    }, { onConflict: 'profile_id,provider' });

  if (privateUpsert.error) {
    console.error('Failed to upsert private provider credentials', privateUpsert.error);
    throw new Error('Failed to persist provider credentials.');
  }

  return publicUpsert.data as ProviderSessionRow;
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

async function resolveUserId(
  supabaseUrl: string,
  requestApiKey: string,
  authorization: string,
): Promise<string | null> {
  const token = authorization.replace(/^Bearer\s+/i, '').trim();
  if (!token) {
    return null;
  }

  const client = createClient(supabaseUrl, requestApiKey, {
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

  const { data, error } = await client.auth.getClaims(token);
  if (error) {
    return null;
  }

  return typeof data?.claims?.sub === 'string' && data.claims.sub.trim()
    ? data.claims.sub.trim()
    : null;
}
