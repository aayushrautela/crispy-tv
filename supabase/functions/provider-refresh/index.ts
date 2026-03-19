import 'jsr:@supabase/functions-js/edge-runtime.d.ts';

import { createClient } from 'jsr:@supabase/supabase-js@2';

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

type ProviderName = 'trakt' | 'simkl';

type ProviderCredentialRow = {
  profile_id: string;
  provider: ProviderName;
  refresh_token: string | null;
  access_token: string | null;
  access_token_expires_at: string | null;
  provider_user_id: string | null;
  provider_username: string | null;
};

type ProviderAccountRow = {
  connected_at: string | null;
};

function jsonResponse(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      ...corsHeaders,
      'Content-Type': 'application/json',
    },
  });
}

function requireEnv(name: string): string {
  const value = Deno.env.get(name)?.trim() ?? '';
  if (!value) {
    throw new Error(`Missing ${name}.`);
  }
  return value;
}

function requireFirstEnv(...names: string[]): string {
  for (const name of names) {
    const value = Deno.env.get(name)?.trim() ?? '';
    if (value) {
      return value;
    }
  }
  throw new Error(`Missing one of: ${names.join(', ')}.`);
}

function configuredPublicApiKey(): string {
  return requireEnv('SB_PUBLISHABLE_KEY');
}

function normalizeProvider(raw: unknown): ProviderName | null {
  const value = typeof raw === 'string' ? raw.trim().toLowerCase() : '';
  if (value === 'trakt' || value === 'simkl') {
    return value;
  }
  return null;
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

async function fetchTraktProfile(accessToken: string, clientId: string) {
  const response = await fetch('https://api.trakt.tv/users/settings', {
    method: 'GET',
    headers: {
      Authorization: `Bearer ${accessToken}`,
      'Content-Type': 'application/json',
      'trakt-api-key': clientId,
      'trakt-api-version': '2',
    },
  });

  if (!response.ok) {
    return {
      providerUserId: null,
      providerUsername: null,
    };
  }

  const payload = (await response.json().catch(() => null)) as Record<string, unknown> | null;
  const user = payload && typeof payload.user === 'object' && payload.user !== null ? payload.user as Record<string, unknown> : null;
  const ids = user && typeof user.ids === 'object' && user.ids !== null ? user.ids as Record<string, unknown> : null;

  return {
    providerUserId: typeof ids?.slug === 'string' ? ids.slug : null,
    providerUsername: typeof user?.username === 'string' ? user.username : null,
  };
}

async function fetchSimklSettings(accessToken: string, clientId: string) {
  const response = await fetch('https://api.simkl.com/users/settings', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${accessToken}`,
      'Content-Type': 'application/json',
      'simkl-api-key': clientId,
    },
  });

  if (!response.ok) {
    return {
      providerUserId: null,
      providerUsername: null,
    };
  }

  const payload = (await response.json().catch(() => null)) as Record<string, unknown> | null;
  const account = payload && typeof payload.account === 'object' && payload.account !== null ? payload.account as Record<string, unknown> : null;
  const user = payload && typeof payload.user === 'object' && payload.user !== null ? payload.user as Record<string, unknown> : null;

  return {
    providerUserId:
      typeof account?.id === 'number' || typeof account?.id === 'string' ? String(account.id) : null,
    providerUsername: typeof user?.name === 'string' ? user.name : null,
  };
}

function expiresAtIsoFromNow(expiresIn: unknown): string | null {
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

async function refreshTraktToken(refreshToken: string) {
  const clientId = requireEnv('TRAKT_CLIENT_ID');
  const clientSecret = requireEnv('TRAKT_CLIENT_SECRET');
  const redirectUri = requireEnv('TRAKT_REDIRECT_URI');

  const response = await fetch('https://api.trakt.tv/oauth/token', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'trakt-api-key': clientId,
      'trakt-api-version': '2',
    },
    body: JSON.stringify({
      refresh_token: refreshToken,
      client_id: clientId,
      client_secret: clientSecret,
      redirect_uri: redirectUri,
      grant_type: 'refresh_token',
    }),
  });

  const payload = (await response.json().catch(() => null)) as Record<string, unknown> | null;
  if (!response.ok || !payload || typeof payload.access_token !== 'string') {
    return {
      error:
        typeof payload?.error_description === 'string'
          ? payload.error_description
          : typeof payload?.error === 'string'
            ? payload.error
            : 'Unable to refresh the Trakt token.',
    };
  }

  const profile = await fetchTraktProfile(payload.access_token, clientId);
  return {
    accessToken: payload.access_token,
    refreshToken: typeof payload.refresh_token === 'string' ? payload.refresh_token : refreshToken,
    accessTokenExpiresAt: expiresAtIsoFromNow(payload.expires_in),
    providerUserId: profile.providerUserId,
    providerUsername: profile.providerUsername,
    error: null,
  };
}

async function refreshSimklToken(refreshToken: string) {
  const clientId = requireEnv('SIMKL_CLIENT_ID');
  const clientSecret = requireEnv('SIMKL_CLIENT_SECRET');
  const redirectUri = requireEnv('SIMKL_REDIRECT_URI');

  const response = await fetch('https://api.simkl.com/oauth/token', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      refresh_token: refreshToken,
      client_id: clientId,
      client_secret: clientSecret,
      redirect_uri: redirectUri,
      grant_type: 'refresh_token',
    }),
  });

  const payload = (await response.json().catch(() => null)) as Record<string, unknown> | null;
  if (!response.ok || !payload || typeof payload.access_token !== 'string') {
    return {
      error:
        typeof payload?.error_description === 'string'
          ? payload.error_description
          : typeof payload?.error === 'string'
            ? payload.error
            : 'Unable to refresh the Simkl token.',
    };
  }

  const profile = await fetchSimklSettings(payload.access_token, clientId);
  return {
    accessToken: payload.access_token,
    refreshToken: typeof payload.refresh_token === 'string' ? payload.refresh_token : refreshToken,
    accessTokenExpiresAt: expiresAtIsoFromNow(payload.expires_in),
    providerUserId: profile.providerUserId,
    providerUsername: profile.providerUsername,
    error: null,
  };
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders });
  }

  if (req.method !== 'POST') {
    return jsonResponse(405, { error: 'Method not allowed.' });
  }

  try {
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

    const body = await req.json().catch(() => null);
    const profileId = typeof body?.profileId === 'string' ? body.profileId.trim() : '';
    const provider = normalizeProvider(body?.provider);

    if (!profileId || !provider) {
      return jsonResponse(400, { error: 'Missing profileId or provider.' });
    }

    const adminClient = createClient(supabaseUrl, adminApiKey, {
      auth: {
        persistSession: false,
        autoRefreshToken: false,
        detectSessionInUrl: false,
      },
    });

    const membershipResult = await adminClient
      .from('profiles')
      .select('id, household_id, household_members!inner(user_id)')
      .eq('id', profileId)
      .eq('household_members.user_id', userId)
      .limit(1)
      .maybeSingle();

    if (membershipResult.error || !membershipResult.data) {
      return jsonResponse(403, { error: 'Not authorized for profile.' });
    }

    const credentialResult = await adminClient
      .schema('private')
      .from('provider_credentials')
      .select('profile_id, provider, refresh_token, access_token, access_token_expires_at, provider_user_id, provider_username')
      .eq('profile_id', profileId)
      .eq('provider', provider)
      .limit(1)
      .maybeSingle();

    if (credentialResult.error) {
      console.error('Failed to load provider credentials', credentialResult.error);
      return jsonResponse(500, { error: 'Failed to load provider credentials.' });
    }

    const credential = credentialResult.data as ProviderCredentialRow | null;
    if (!credential) {
      return jsonResponse(404, { error: 'Provider is not connected for this profile.' });
    }

    const providerAccountResult = await adminClient
      .from('provider_accounts')
      .select('connected_at')
      .eq('profile_id', profileId)
      .eq('provider', provider)
      .limit(1)
      .maybeSingle();

    if (providerAccountResult.error) {
      console.error('Failed to load provider account', providerAccountResult.error);
      return jsonResponse(500, { error: 'Failed to load provider account.' });
    }

    const providerAccount = providerAccountResult.data as ProviderAccountRow | null;
    const nowIso = new Date().toISOString();
    const connectedAt = providerAccount?.connected_at ?? nowIso;

    const refreshToken = credential.refresh_token?.trim() ?? '';
    if (!refreshToken) {
      return jsonResponse(400, { error: 'Refresh token is unavailable for this provider.' });
    }

    const refreshed = provider === 'trakt'
      ? await refreshTraktToken(refreshToken)
      : await refreshSimklToken(refreshToken);

    if (refreshed.error) {
      await adminClient
        .from('provider_accounts')
        .update({
          last_refresh_at: nowIso,
          last_refresh_error: refreshed.error,
        })
        .eq('profile_id', profileId)
        .eq('provider', provider);

      await adminClient
        .schema('private')
        .from('provider_credentials')
        .update({
          last_refresh_at: nowIso,
          last_refresh_error: refreshed.error,
        })
        .eq('profile_id', profileId)
        .eq('provider', provider);

      return jsonResponse(502, { error: refreshed.error });
    }

    const upsertPayload = {
      profile_id: profileId,
      provider,
      access_token: refreshed.accessToken,
      access_token_expires_at: refreshed.accessTokenExpiresAt,
      provider_user_id: refreshed.providerUserId,
      provider_username: refreshed.providerUsername,
      connected_at: connectedAt,
      last_refresh_at: nowIso,
      last_refresh_error: null,
    };

    const publicUpsert = await adminClient
      .from('provider_accounts')
      .upsert(upsertPayload, { onConflict: 'profile_id,provider' })
      .select('access_token, access_token_expires_at, provider_username, provider_user_id, connected_at')
      .single();

    if (publicUpsert.error) {
      console.error('Failed to upsert provider account', publicUpsert.error);
      return jsonResponse(500, { error: 'Failed to update provider account.' });
    }

    const privateUpsert = await adminClient
      .schema('private')
      .from('provider_credentials')
      .upsert({
        profile_id: profileId,
        provider,
        refresh_token: refreshed.refreshToken,
        access_token: refreshed.accessToken,
        access_token_expires_at: refreshed.accessTokenExpiresAt,
        provider_user_id: refreshed.providerUserId,
        provider_username: refreshed.providerUsername,
        last_refresh_at: nowIso,
        last_refresh_error: null,
      }, { onConflict: 'profile_id,provider' });

    if (privateUpsert.error) {
      console.error('Failed to upsert provider credentials', privateUpsert.error);
      return jsonResponse(500, { error: 'Failed to update provider credentials.' });
    }

    return jsonResponse(200, {
      session: publicUpsert.data,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Unexpected provider refresh error.';
    return jsonResponse(500, { error: message });
  }
});
