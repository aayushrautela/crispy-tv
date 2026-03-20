import 'jsr:@supabase/functions-js/edge-runtime.d.ts';

import {
  authenticateRequest,
  authorizeProfileAccess,
  corsHeaders,
  deleteProviderSession,
  jsonResponse,
  normalizeProvider,
} from '../_shared/providerAuth.ts';

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

    await deleteProviderSession(auth.adminClient, profileId, provider);

    return jsonResponse(200, { success: true });
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Unexpected provider disconnect error.';
    return jsonResponse(500, { error: message });
  }
});
