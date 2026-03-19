import 'jsr:@supabase/functions-js/edge-runtime.d.ts';

import {
  authenticateRequest,
  authorizeProfileAccess,
  corsHeaders,
  expiresAtIsoFromNow,
  isRecord,
  jsonResponse,
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
  const user = isRecord(payload?.user) ? payload.user : null;
  const ids = user && isRecord(user.ids) ? user.ids : null;

  return {
    providerUserId: typeof ids?.slug === 'string' ? ids.slug : null,
    providerUsername: typeof user?.username === 'string' ? user.username : null,
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

    const clientId = requireEnv('TRAKT_CLIENT_ID');
    const clientSecret = requireEnv('TRAKT_CLIENT_SECRET');
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

    if (!(await authorizeProfileAccess(auth.adminClient, profileId, auth.userId))) {
      return jsonResponse(403, { error: 'Not authorized for profile.' });
    }

    const redirectUri = requireEnv('TRAKT_REDIRECT_URI');
    if (redirectUriRaw !== redirectUri) {
      return jsonResponse(400, { error: 'Redirect URI mismatch.' });
    }

    const response = await fetch('https://api.trakt.tv/oauth/token', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'trakt-api-key': clientId,
        'trakt-api-version': '2',
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
              : 'Unable to exchange the Trakt authorization code.',
      });
    }

    const profile = await fetchTraktProfile(payload.access_token, clientId);
    const accessTokenExpiresAt = expiresAtIsoFromNow(payload.expires_in);
    const nowIso = new Date().toISOString();

    const session = await upsertProviderSession(auth.adminClient, {
      profileId,
      provider: 'trakt',
      accessToken: payload.access_token,
      accessTokenExpiresAt,
      providerUserId: profile.providerUserId,
      providerUsername: profile.providerUsername,
      refreshToken: typeof payload.refresh_token === 'string' ? payload.refresh_token : null,
      connectedAt: nowIso,
      lastRefreshAt: nowIso,
      lastRefreshError: null,
    });

    return jsonResponse(200, {
      ...payload,
      ...profile,
      access_token_expires_at: accessTokenExpiresAt,
      session,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Unexpected Trakt OAuth error.';
    return jsonResponse(500, { error: message });
  }
});
