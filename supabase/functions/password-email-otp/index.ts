import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const json = (body: Record<string, unknown>, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });

const env = (key: string) => {
  const value = Deno.env.get(key);
  if (!value) throw new Error("Server configuration is missing: " + key);
  return value;
};

const maskEmail = (email: string) => {
  const [name, domain] = email.split("@");
  if (!domain) return "your email";
  return (name.length < 3 ? name[0] + "*" : name.slice(0, 2) + "***") + "@" + domain;
};

async function hashCode(challengeId: string, code: string, secret: string) {
  const bytes = new TextEncoder().encode(challengeId + ":" + code + ":" + secret);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function makeCode() {
  const bytes = new Uint32Array(1);
  crypto.getRandomValues(bytes);
  return String(bytes[0] % 1_000_000).padStart(6, "0");
}

const normalizeEmail = (v: unknown) => String(v || "").trim().toLowerCase();
const validEmail = (v: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v);

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return json({ error: "Method not allowed." }, 405);

  try {
    const body = await req.json();
    const action = String(body.action || "");
    const email = normalizeEmail(body.email);
    const password = String(body.password || "");
    if (!validEmail(email) || !password) return json({ error: "Enter a valid email and password." }, 400);

    const url = env("SUPABASE_URL");
    const anonKey = env("SUPABASE_ANON_KEY");
    const serviceKey = env("SUPABASE_SERVICE_ROLE_KEY");
    const authClient = createClient(url, anonKey, {
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
    });
    const adminClient = createClient(url, serviceKey, {
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
    });

    if (action === "request") {
      // Verify the password with Supabase Auth. Do not store it.
      const { data: verified, error: passwordError } = await authClient.auth.signInWithPassword({ email, password });
      if (passwordError || !verified.user || !verified.session) {
        return json({ error: "Email or password is incorrect, or this account cannot sign in." }, 401);
      }
      const userId = verified.user.id;
      // The temporary server-side auth client does not persist its session.
      // Do not call signOut here: Supabase's default signOut scope can revoke other devices.

      // Supabase-side rate limiting: at most five challenges per email in 15 minutes.
      const since = new Date(Date.now() - 15 * 60 * 1000).toISOString();
      const { data: recent, error: rateError } = await adminClient
        .from("ps_login_otp_challenges").select("id").eq("email", email).gte("created_at", since).limit(5);
      if (rateError) return json({ error: "Login verification is temporarily unavailable. Please try again." }, 503);
      if ((recent || []).length >= 5) return json({ error: "Too many verification requests. Please wait 15 minutes." }, 429);

      const challengeId = crypto.randomUUID();
      const code = makeCode();
      const codeHash = await hashCode(challengeId, code, serviceKey);
      const expiresAt = new Date(Date.now() + 5 * 60 * 1000).toISOString();
      const { error: insertError } = await adminClient.from("ps_login_otp_challenges").insert({
        id: challengeId, user_id: userId, email, code_hash: codeHash, expires_at: expiresAt,
      });
      if (insertError) return json({ error: "Could not create a verification challenge. Apply the PaySaathi migration and retry." }, 503);

      const resendKey = env("RESEND_API_KEY");
      const from = env("OTP_FROM_EMAIL");
      const mail = await fetch("https://api.resend.com/emails", {
        method: "POST",
        headers: { "Authorization": "Bearer " + resendKey, "Content-Type": "application/json" },
        body: JSON.stringify({
          from, to: [email], subject: "Your PaySaathi login code",
          text: "Your PaySaathi login code is " + code + ". It expires in 5 minutes. Do not share this code with anyone.",
          html: "<div style=\"font-family:Arial,sans-serif;max-width:520px;margin:auto;padding:28px;color:#10243a\"><h2>PaySaathi login verification</h2><p>Use this 6-digit code to finish signing in:</p><div style=\"font-size:32px;letter-spacing:9px;font-weight:800;padding:18px;background:#eef6ff;border-radius:12px;text-align:center\">" + code + "</div><p>This code expires in 5 minutes. Never share it with anyone.</p></div>",
        }),
      });
      if (!mail.ok) {
        await adminClient.from("ps_login_otp_challenges").delete().eq("id", challengeId);
        return json({ error: "The email service could not send your code. Check the email provider configuration." }, 502);
      }
      return json({ challengeId, maskedEmail: maskEmail(email), expiresIn: 300 });
    }

    if (action === "verify") {
      const challengeId = String(body.challengeId || "");
      const code = String(body.code || "");
      if (!/^[0-9a-f-]{36}$/i.test(challengeId) || !/^\d{6}$/.test(code)) {
        return json({ error: "Enter the 6-digit code from your email." }, 400);
      }
      const { data: challenge, error: lookupError } = await adminClient
        .from("ps_login_otp_challenges").select("id,user_id,email,code_hash,attempts,expires_at,consumed_at")
        .eq("id", challengeId).eq("email", email).maybeSingle();
      if (lookupError || !challenge) return json({ error: "This verification code is invalid or expired. Request a new code." }, 400);
      if (challenge.consumed_at || new Date(challenge.expires_at).getTime() <= Date.now()) {
        return json({ error: "This verification code has expired. Request a new code." }, 400);
      }
      if (Number(challenge.attempts) >= 5) return json({ error: "Too many incorrect attempts. Request a new code." }, 429);

      const expected = await hashCode(challengeId, code, serviceKey);
      const { error: attemptError } = await adminClient.from("ps_login_otp_challenges")
        .update({ attempts: Number(challenge.attempts) + 1 }).eq("id", challengeId);
      if (attemptError) return json({ error: "Could not verify this code. Please try again." }, 503);
      if (expected !== challenge.code_hash) return json({ error: "The verification code is incorrect." }, 400);

      // Atomically consume the code before issuing a session so it cannot be replayed.
      const { data: consumed, error: consumeError } = await adminClient.from("ps_login_otp_challenges")
        .update({ consumed_at: new Date().toISOString() }).eq("id", challengeId).is("consumed_at", null)
        .select("id").maybeSingle();
      if (consumeError || !consumed) return json({ error: "This verification code has already been used. Request a new code." }, 400);

      // Re-check the password at verification time and only then issue a session.
      const { data: signedIn, error: signInError } = await authClient.auth.signInWithPassword({ email, password });
      if (signInError || !signedIn.session || signedIn.user?.id !== challenge.user_id) {
        return json({ error: "Password verification failed. Start login again." }, 401);
      }
      return json({ session: signedIn.session });
    }

    return json({ error: "Unknown action." }, 400);
  } catch (error) {
    console.error("password-email-otp:", error instanceof Error ? error.message : "unknown error");
    return json({ error: "Login verification is temporarily unavailable. Check the server configuration and try again." }, 500);
  }
});
