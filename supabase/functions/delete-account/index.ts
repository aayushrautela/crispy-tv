import 'jsr:@supabase/functions-js/edge-runtime.d.ts';

import { createClient } from 'jsr:@supabase/supabase-js@2';

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

type MembershipRow = {
  household_id: string;
  role: 'owner' | 'member';
};

type MemberRow = {
  user_id: string;
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

async function removeOwnedAvatars(adminClient: ReturnType<typeof createClient>, userId: string) {
  const { data, error } = await adminClient.storage.from('avatars').list(userId, {
    limit: 1000,
  });

  if (error) {
    throw error;
  }

  const paths = (data ?? []).map((entry) => `${userId}/${entry.name}`);

  if (!paths.length) {
    return;
  }

  const { error: removeError } = await adminClient.storage.from('avatars').remove(paths);

  if (removeError) {
    throw removeError;
  }
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

    const adminClient = createClient(supabaseUrl, adminApiKey, {
      auth: {
        persistSession: false,
        autoRefreshToken: false,
        detectSessionInUrl: false,
      },
    });

    const membershipResult = await adminClient
      .from('household_members')
      .select('household_id, role')
      .eq('user_id', userId)
      .maybeSingle();

    const membership = membershipResult.data as MembershipRow | null;
    const membershipError = membershipResult.error;

    if (membershipError) {
      console.error('Failed to resolve membership for account deletion', membershipError);
      return jsonResponse(500, { error: 'Failed to resolve household membership.' });
    }

    let replacementMemberId: string | null = null;

    if (membership?.role === 'owner') {
      const replacementResult = await adminClient
        .from('household_members')
        .select('user_id')
        .eq('household_id', membership.household_id)
        .neq('user_id', userId)
        .order('created_at', { ascending: true })
        .limit(1)
        .maybeSingle();

      const replacementMember = replacementResult.data as MemberRow | null;
      const replacementError = replacementResult.error;

      if (replacementError) {
        console.error('Failed to select replacement household owner', replacementError);
        return jsonResponse(500, { error: 'Failed to prepare household ownership transfer.' });
      }

      replacementMemberId = replacementMember?.user_id ?? null;
    }

    try {
      await removeOwnedAvatars(adminClient, userId);
    } catch (error) {
      console.error('Failed to remove owned avatars before account deletion', error);
      return jsonResponse(500, { error: 'Failed to clean up avatar uploads for this account.' });
    }

    const { error: deleteUserError } = await adminClient.auth.admin.deleteUser(userId);

    if (deleteUserError) {
      console.error('Failed to delete auth user', deleteUserError);
      return jsonResponse(500, { error: deleteUserError.message || 'Failed to delete account.' });
    }

    if (membership?.role === 'owner') {
      if (replacementMemberId) {
        const { error: promoteError } = await adminClient
          .from('household_members')
          .update({ role: 'owner' })
          .eq('household_id', membership.household_id)
          .eq('user_id', replacementMemberId);

        if (promoteError) {
          console.error('Deleted account but failed to promote replacement owner', promoteError);
          return jsonResponse(200, {
            success: true,
            warning: 'Account deleted, but household ownership needs manual review.',
          });
        }

        const { error: updateHouseholdError } = await adminClient
          .from('households')
          .update({ owner_user_id: replacementMemberId })
          .eq('id', membership.household_id);

        if (updateHouseholdError) {
          console.error('Deleted account but failed to update household owner metadata', updateHouseholdError);
          return jsonResponse(200, {
            success: true,
            warning: 'Account deleted, but household ownership metadata needs manual review.',
          });
        }
      } else {
        const { error: deleteHouseholdError } = await adminClient
          .from('households')
          .delete()
          .eq('id', membership.household_id);

        if (deleteHouseholdError) {
          console.error('Deleted account but failed to remove orphaned household', deleteHouseholdError);
          return jsonResponse(200, {
            success: true,
            warning: 'Account deleted, but household cleanup needs manual review.',
          });
        }
      }
    }

    return jsonResponse(200, { success: true });
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Unexpected account deletion error.';
    return jsonResponse(500, { error: message });
  }
});
