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

function normalizeProvider(raw: unknown): 'trakt' | 'simkl' | null {
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

    const publicDelete = await adminClient
      .from('provider_accounts')
      .delete()
      .eq('profile_id', profileId)
      .eq('provider', provider);

    if (publicDelete.error) {
      console.error('Failed to delete provider account', publicDelete.error);
      return jsonResponse(500, { error: 'Failed to disconnect provider.' });
    }

    const privateDelete = await adminClient
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
