import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Content-Type": "application/json",
};

const reply = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: cors });

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return reply({ error: "Method not allowed" }, 405);

  try {
    const url = Deno.env.get("SUPABASE_URL");
    const anon = Deno.env.get("SUPABASE_ANON_KEY");
    const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!url || !anon || !service) return reply({ error: "Server configuration unavailable" }, 500);

    const auth = req.headers.get("Authorization");
    if (!auth?.startsWith("Bearer ")) return reply({ error: "Sign in required" }, 401);

    const userClient = createClient(url, anon, {
      global: { headers: { Authorization: auth } },
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: userData, error: userError } = await userClient.auth.getUser();
    if (userError || !userData.user) return reply({ error: "Invalid session" }, 401);

    const body = await req.json().catch(() => ({}));
    const domainId = typeof body.domain_id === "string" ? body.domain_id : "";
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(domainId)) {
      return reply({ error: "A valid domain registration ID is required" }, 400);
    }

    const { data: memberships, error: memberError } = await userClient.rpc("ps_tenant_list_available");
    if (memberError) return reply({ error: "Could not verify business permissions" }, 403);
    const allowedTenantIds = (Array.isArray(memberships) ? memberships : [])
      .filter((m: any) => ["owner", "admin"].includes(m.role))
      .map((m: any) => String(m.id));

    const admin = createClient(url, service, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: domain, error: domainError } = await admin
      .from("ps_tenant_domains")
      .select("id,tenant_id,hostname,verification_token,status")
      .eq("id", domainId)
      .maybeSingle();

    if (domainError || !domain || !allowedTenantIds.includes(String(domain.tenant_id))) {
      return reply({ error: "Domain not found or you do not have permission to verify it" }, 404);
    }
    if (domain.status === "verified") return reply({ verified: true, hostname: domain.hostname, message: "Domain is already verified." });
    if (domain.status !== "pending") return reply({ error: "This domain is not awaiting verification" }, 409);

    const hostname = String(domain.hostname || "").toLowerCase();
    if (!/^(?=.{4,253}$)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])$/.test(hostname) || hostname.includes("..")) {
      return reply({ error: "Stored hostname is invalid" }, 400);
    }

    const recordName = "_paysaathi-verification." + hostname;
    const dnsResponse = await fetch("https://dns.google/resolve?name=" + encodeURIComponent(recordName) + "&type=TXT", {
      headers: { "Accept": "application/dns-json" },
      signal: AbortSignal.timeout(8000),
    });
    if (!dnsResponse.ok) return reply({ error: "DNS verification service is temporarily unavailable. Try again later." }, 502);
    const dns = await dnsResponse.json();
    const answers = Array.isArray(dns.Answer) ? dns.Answer : [];
    const expected = "paysaathi-verification=" + String(domain.verification_token);
    const found = answers.some((a: any) => {
      if (a.type !== 16 || typeof a.data !== "string") return false;
      const normalized = a.data.replace(/^"|"$/g, "").replace(/"\s*"/g, "").replace(/\\([\\"])/g, "$1");
      return normalized === expected;
    });
    if (!found) {
      return reply({
        verified: false,
        hostname,
        record_name: recordName,
        record_type: "TXT",
        expected_value: expected,
        message: "The DNS TXT record was not found yet. Add the exact record and retry after DNS propagation.",
      }, 200);
    }

    const { error: updateError } = await admin
      .from("ps_tenant_domains")
      .update({ status: "verified", verified_at: new Date().toISOString() })
      .eq("id", domain.id)
      .eq("tenant_id", domain.tenant_id)
      .eq("status", "pending");
    if (updateError) return reply({ error: "DNS matched, but the domain status could not be saved. Retry verification." }, 500);

    await admin.from("ps_tenant_audit_log").insert({
      tenant_id: domain.tenant_id,
      actor_id: userData.user.id,
      action: "tenant.domain.verified",
      target_type: "domain",
      target_id: domain.id,
    });

    return reply({ verified: true, hostname, message: "Domain ownership verified. DNS routing and SSL still need to be configured at your hosting provider." });
  } catch (error) {
    return reply({ error: "Domain verification failed safely. Please retry." }, 500);
  }
});