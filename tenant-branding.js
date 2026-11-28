/* Applies the active signed-in workspace brand, or verified custom-domain brand. */
(async () => {
  "use strict";
  const SUPABASE_URL = "https://hhqcycwfvxoxjizlbivv.supabase.co";
  const SUPABASE_KEY = "sb_publishable_NdrUA-ubf67oW5hiS-Yw4A_-r2xbJRV";
  const host = String(location.hostname || "").toLowerCase();
  const isPlatformHost = !host || host.endsWith(".github.io") || host === "localhost";

  function applyBrand(data) {
    if (!data || typeof data !== "object") return;
    const brand = String(data.brand_name || "").trim();
    if (!brand) return;
    const primary = /^#[0-9a-f]{6}$/i.test(data.primary_color || "") ? data.primary_color : null;
    const accent = /^#[0-9a-f]{6}$/i.test(data.accent_color || "") ? data.accent_color : null;
    const root = document.documentElement;
    if (primary) {
      root.style.setProperty("--blue", primary);
      root.style.setProperty("--primary", primary);
      root.style.setProperty("--brand-primary", primary);
    }
    if (accent) {
      root.style.setProperty("--cyan", accent);
      root.style.setProperty("--accent", accent);
      root.style.setProperty("--brand-accent", accent);
    }

    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    for (const node of nodes) {
      if (node.nodeValue && /PaySaathi/i.test(node.nodeValue)) {
        node.nodeValue = node.nodeValue.replace(/PaySaathi/gi, brand);
      }
    }
    if (document.title) document.title = document.title.replace(/PaySaathi/gi, brand);
    document.querySelectorAll('meta[name="description"]').forEach(meta => {
      if (meta.content) meta.content = meta.content.replace(/PaySaathi/gi, brand);
    });
    document.querySelectorAll("[data-brand-name]").forEach(el => { el.textContent = brand; });
    document.querySelectorAll("[data-brand-tagline]").forEach(el => { el.textContent = data.tagline || ""; });

    const logo = typeof data.logo_url === "string" && /^https:\/\//i.test(data.logo_url) ? data.logo_url : "";
    document.querySelectorAll("[data-brand-logo]").forEach(el => {
      if (logo) {
        if (el instanceof HTMLImageElement) el.src = logo;
        else el.style.backgroundImage = 'url("' + logo.replace(/["\\\\]/g, "") + '")';
      }
    });
    if (logo) {
      document.querySelectorAll(".brand .logo,.brand .mark").forEach(el => {
        el.style.backgroundImage = 'url("' + logo.replace(/["\\\\]/g, "") + '")';
        el.style.backgroundSize = "contain";
        el.style.backgroundPosition = "center";
        el.style.backgroundRepeat = "no-repeat";
        el.style.color = "transparent";
      });
    }
    if (typeof data.favicon_url === "string" && /^https:\/\//i.test(data.favicon_url)) {
      let icon = document.querySelector('link[rel~="icon"]');
      if (!icon) { icon = document.createElement("link"); icon.rel = "icon"; document.head.appendChild(icon); }
      icon.href = data.favicon_url;
    }
    const theme = document.querySelector('meta[name="theme-color"]');
    if (theme && primary) theme.content = primary;
    if (data.hide_platform_branding === true) {
      document.querySelectorAll("[data-platform-credit]").forEach(el => el.remove());
    }
    root.dataset.tenantBrandApplied = "true";
  }

  try {
    const { createClient } = await import("https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2/+esm");
    const client = createClient(SUPABASE_URL, SUPABASE_KEY, {
      auth: {
        storageKey: "paysaathi.auth.v1",
        persistSession: true,
        autoRefreshToken: true,
        detectSessionInUrl: false
      }
    });

    // On the shared PaySaathi host, use the authenticated user's selected workspace.
    // On a customer-owned hostname, only the server-verified public brand is returned.
    if (isPlatformHost) {
      const { data: sessionData } = await client.auth.getSession();
      if (!sessionData?.session) return;
      const { data, error } = await client.rpc("ps_tenant_get_my_branding");
      if (!error) applyBrand(data);
      return;
    }

    const { data, error } = await client.rpc("ps_tenant_get_public_branding", { p_hostname: host });
    if (!error) applyBrand(data);
  } catch (error) {
    console.warn("Tenant branding could not be applied; keeping the default PaySaathi appearance.", error);
  }
})();
