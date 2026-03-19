import 'jsr:@supabase/functions-js/edge-runtime.d.ts';

import {
  authenticateRequest,
  authorizeProfileAccess,
  corsHeaders,
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

    const publicDelete = await auth.adminClient
      .from('provider_accounts')
      .delete()
      .eq('profile_id', profileId)
      .eq('provider', provider);

    if (publicDelete.error) {
      console.error('Failed to delete provider account', publicDelete.error);
      return jsonResponse(500, { error: 'Failed to disconnect provider.' });
    }

    const privateDelete = await auth.adminClient
      .schema('private')
      .from('provider_credentials')
      .delete()
      .eq('profile_id', profileId)
      .eq('provider', provider);

    if (privateDelete.error) {
      console.error('Failed to delete provider credentials', privateDelete.error);
      return jsonResponse(500, { error: 'Failed to remove provider credentials.' });
    }

    return jsonResponse(200, { success: true });
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Unexpected provider disconnect error.';
    return jsonResponse(500, { error: message });
  }
});
