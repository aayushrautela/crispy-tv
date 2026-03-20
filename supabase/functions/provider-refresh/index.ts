import 'jsr:@supabase/functions-js/edge-runtime.d.ts';

import {
  authenticateRequest,
  authorizeProfileAccess,
  corsHeaders,
  expiresAtIsoFromNow,
  loadProviderCredentials,
  markProviderRefreshError,
  jsonResponse,
  loadConnectedAt,
  normalizeProvider,
  requireEnv,
  upsertProviderSession,
} from '../_shared/providerAuth.ts';

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
    const auth = await authenticateRequest(req);
    if (auth instanceof Response) {
      return auth;
    }

    const body = await req.json().catch(() => null);
    const profileId = typeof body?.profileId === 'string' ? body.profileId.trim() : '';
    const provider = normalizeProvider(body?.provider);

    if (!profileId || !provider) {
      return jsonResponse(400, { error: 'Missing profileId or provider.' });
    }

    if (!(await authorizeProfileAccess(auth.adminClient, profileId, auth.userId))) {
      return jsonResponse(403, { error: 'Not authorized for profile.' });
    }

    const credential = await loadProviderCredentials(auth.adminClient, profileId, provider);
    if (!credential) {
      return jsonResponse(404, { error: 'Provider is not connected for this profile.' });
    }

    const nowIso = new Date().toISOString();
    const connectedAt = (await loadConnectedAt(auth.adminClient, profileId, provider)) ?? nowIso;

    const refreshToken = credential.refresh_token?.trim() ?? '';
    if (!refreshToken) {
      return jsonResponse(400, { error: 'Refresh token is unavailable for this provider.' });
    }

    const refreshed = provider === 'trakt'
      ? await refreshTraktToken(refreshToken)
      : await refreshSimklToken(refreshToken);

    if (refreshed.error) {
      try {
        await markProviderRefreshError(auth.adminClient, profileId, provider, nowIso, refreshed.error);
      } catch (persistError) {
        console.error('Failed to persist provider refresh error', persistError);
      }

      return jsonResponse(502, { error: refreshed.error });
    }

    const session = await upsertProviderSession(auth.adminClient, {
      profileId,
      provider,
      accessToken: refreshed.accessToken,
      accessTokenExpiresAt: refreshed.accessTokenExpiresAt,
      providerUserId: refreshed.providerUserId,
      providerUsername: refreshed.providerUsername,
      refreshToken: refreshed.refreshToken,
      connectedAt,
      lastRefreshAt: nowIso,
      lastRefreshError: null,
    });

    return jsonResponse(200, {
      session,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Unexpected provider refresh error.';
    return jsonResponse(500, { error: message });
  }
});
