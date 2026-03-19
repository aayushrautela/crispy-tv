import 'jsr:@supabase/functions-js/edge-runtime.d.ts';

import { createClient } from 'jsr:@supabase/supabase-js@2';

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function readRequiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim();

  if (!value) {
    throw new Error(`${name} is not configured.`);
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
  return readRequiredEnv('SB_PUBLISHABLE_KEY');
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
  const account = payload && isRecord(payload.account) ? payload.account : null;
  const user = payload && isRecord(payload.user) ? payload.user : null;

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

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders });
  }

  if (req.method !== 'POST') {
    return jsonResponse(405, { error: 'Method not allowed.' });
  }

  try {
    const supabaseUrl = readRequiredEnv('SUPABASE_URL');
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

    const clientId = readRequiredEnv('SIMKL_CLIENT_ID');
    const clientSecret = readRequiredEnv('SIMKL_CLIENT_SECRET');
    const body = (await req.json().catch(() => null)) as Record<string, unknown> | null;
    const profileId = typeof body?.profileId === 'string' ? body.profileId.trim() : '';
    const code = typeof body?.code === 'string' ? body.code.trim() : '';
    const codeVerifier = typeof body?.codeVerifier === 'string' ? body.codeVerifier.trim() : '';
    const redirectUriRaw =
      typeof body?.redirectUri === 'string'
        ? body.redirectUri.trim()
        : typeof body?.redirect_uri === 'string'
          ? body.redirect_uri.trim()
          : '';

    if (!profileId || !code || !codeVerifier || !redirectUriRaw) {
      return jsonResponse(400, { error: 'Missing profileId, code, codeVerifier, or redirectUri.' });
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

    const redirectUri = readRequiredEnv('SIMKL_REDIRECT_URI');
    if (redirectUriRaw !== redirectUri) {
      return jsonResponse(400, { error: 'Redirect URI mismatch.' });
    }

    const response = await fetch('https://api.simkl.com/oauth/token', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        code,
        client_id: clientId,
        client_secret: clientSecret,
        code_verifier: codeVerifier,
        redirect_uri: redirectUri,
        grant_type: 'authorization_code',
      }),
    });

    const payload = (await response.json().catch(() => null)) as Record<string, unknown> | null;

    if (!response.ok || !payload || typeof payload.access_token !== 'string') {
      return jsonResponse(response.status || 502, {
        error:
          typeof payload?.error_description === 'string'
            ? payload.error_description
            : typeof payload?.error === 'string'
              ? payload.error
              : 'Unable to exchange the SIMKL authorization code.',
      });
    }

    const profile = await fetchSimklSettings(payload.access_token, clientId);
    const accessTokenExpiresAt = expiresAtIsoFromNow(payload.expires_in);
    const nowIso = new Date().toISOString();

    const publicUpsert = await adminClient
      .from('provider_accounts')
      .upsert({
        profile_id: profileId,
        provider: 'simkl',
        access_token: payload.access_token,
        access_token_expires_at: accessTokenExpiresAt,
        provider_user_id: profile.providerUserId,
        provider_username: profile.providerUsername,
        connected_at: nowIso,
        last_refresh_at: nowIso,
        last_refresh_error: null,
      }, { onConflict: 'profile_id,provider' })
      .select('access_token, access_token_expires_at, provider_username, provider_user_id, connected_at')
      .single();

    if (publicUpsert.error) {
      console.error('Failed to upsert public provider account', publicUpsert.error);
      return jsonResponse(500, { error: 'Failed to persist provider account.' });
    }

    const privateUpsert = await adminClient
      .schema('private')
      .from('provider_credentials')
      .upsert({
        profile_id: profileId,
        provider: 'simkl',
        refresh_token: typeof payload.refresh_token === 'string' ? payload.refresh_token : null,
        access_token: payload.access_token,
        access_token_expires_at: accessTokenExpiresAt,
        provider_user_id: profile.providerUserId,
        provider_username: profile.providerUsername,
        last_refresh_at: nowIso,
        last_refresh_error: null,
      }, { onConflict: 'profile_id,provider' });

    if (privateUpsert.error) {
      console.error('Failed to upsert private provider credentials', privateUpsert.error);
      return jsonResponse(500, { error: 'Failed to persist provider credentials.' });
    }

    return jsonResponse(200, {
      ...payload,
      ...profile,
      access_token_expires_at: accessTokenExpiresAt,
      session: publicUpsert.data,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Unexpected SIMKL OAuth error.';
    return jsonResponse(500, { error: message });
  }
});
