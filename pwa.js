/* PaySaathi install and user-approved app updates. */
(() => {
  "use strict";
  const base = new URL("./", location.href);
  const url = path => new URL(path, base).href;
  const KEY = "paysaathi.pwa.install.dismissed";
  let installPrompt = null;
  let registration = null;
  let reloading = false;

  const style = document.createElement("style");
  style.textContent = `
    #psPwaDock{position:fixed;z-index:99999;right:16px;bottom:16px;width:min(370px,calc(100vw - 32px));font:14px/1.5 system-ui,-apple-system,Segoe UI,sans-serif;color:#10243a}
    #psPwaDock .ps-card{background:#fff;border:1px solid #dce8f2;border-radius:18px;box-shadow:0 18px 55px #082b4930;padding:16px}
    #psPwaDock .ps-head{display:flex;align-items:center;gap:11px;margin-bottom:8px}
    #psPwaDock .ps-icon{width:42px;height:42px;border-radius:12px}
    #psPwaDock .ps-title{font-weight:850;font-size:15px;color:#071d35}
    #psPwaDock .ps-sub{font-size:12px;color:#64788b;margin-top:2px}
    #psPwaDock .ps-notes{margin:10px 0 14px;padding-left:19px;color:#425970;font-size:12px}
    #psPwaDock .ps-actions{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
    #psPwaDock button{border:0;border-radius:10px;padding:10px 14px;font-weight:800;cursor:pointer;font-size:12px}
    #psPwaDock .ps-primary{background:linear-gradient(110deg,#139de0,#0872ba);color:white}
    #psPwaDock .ps-secondary{background:#eef5fa;color:#31516d}
    #psPwaDock .ps-close{margin-left:auto;background:transparent;color:#72869a;padding:5px 8px}
    #psPwaDock[hidden]{display:none}
    @media(max-width:600px){#psPwaDock{right:10px;bottom:calc(10px + env(safe-area-inset-bottom));width:calc(100vw - 20px)}}
  `;
  document.head.appendChild(style);

  const dock = document.createElement("aside");
  dock.id = "psPwaDock";
  dock.hidden = true;
  dock.setAttribute("aria-live","polite");
  dock.innerHTML = `
    <div class="ps-card">
      <div class="ps-head">
        <img class="ps-icon" src="${url("assets/paysaathi-app.svg")}" alt="">
        <div style="flex:1;min-width:0"><div class="ps-title" id="psPwaTitle">PaySaathi</div><div class="ps-sub" id="psPwaSub">Install the app for quick access</div></div>
        <button class="ps-close" id="psPwaClose" aria-label="Close">✕</button>
      </div>
      <ul class="ps-notes" id="psPwaNotes" hidden></ul>
      <div class="ps-actions">
        <button class="ps-primary" id="psPwaAction">Install app</button>
        <button class="ps-secondary" id="psPwaLater">Not now</button>
      </div>
    </div>`;
  document.body.appendChild(dock);

  const title = dock.querySelector("#psPwaTitle");
  const sub = dock.querySelector("#psPwaSub");
  const notes = dock.querySelector("#psPwaNotes");
  const action = dock.querySelector("#psPwaAction");
  const later = dock.querySelector("#psPwaLater");
  const close = dock.querySelector("#psPwaClose");
  let mode = "install";

  function showInstall() {
    if (localStorage.getItem(KEY) === "1" || window.matchMedia("(display-mode: standalone)").matches || navigator.standalone) return;
    mode = "install";
    title.textContent = "Install PaySaathi";
    sub.textContent = "Add PaySaathi to your phone like a regular app.";
    notes.hidden = true;
    action.textContent = installPrompt ? "Install app" : "How to install";
    later.textContent = "Not now";
    dock.hidden = false;
  }
  function showUpdate(release) {
    mode = "update";
    title.textContent = release.title || "PaySaathi update available";
    sub.textContent = "Version " + (release.version || "new") + " is ready. Review what changed:";
    notes.innerHTML = "";
    (Array.isArray(release.notes) ? release.notes : ["Performance and compatibility improvements."]).forEach(item => {
      const li = document.createElement("li");
      li.textContent = item;
      notes.appendChild(li);
    });
    notes.hidden = false;
    action.textContent = "Update now";
    later.textContent = "Later";
    dock.hidden = false;
  }
  async function readRelease() {
    try {
      const response = await fetch(url("release.json") + "?t=" + Date.now(), {cache:"no-store"});
      if (!response.ok) throw new Error("Release notes unavailable");
      return await response.json();
    } catch (_) { return {version:"new",title:"PaySaathi update available",notes:["A new version is ready to install."]}; }
  }
  async function checkWaitingWorker() {
    if (registration && registration.waiting && navigator.serviceWorker.controller) {
      showUpdate(await readRelease());
    }
  }

  action.addEventListener("click", async () => {
    if (mode === "update") {
      if (!registration || !registration.waiting) { location.reload(); return; }
      action.disabled = true;
      action.textContent = "Updating…";
      navigator.serviceWorker.addEventListener("controllerchange", () => {
        if (!reloading) { reloading = true; location.reload(); }
      }, {once:true});
      registration.waiting.postMessage({type:"PWAY_SKIP_WAITING"});
      return;
    }
    if (installPrompt) {
      installPrompt.prompt();
      const choice = await installPrompt.userChoice;
      installPrompt = null;
      if (choice && choice.outcome === "accepted") dock.hidden = true;
      else { localStorage.setItem(KEY,"1"); dock.hidden = true; }
      return;
    }
    const isIOS = /iphone|ipad|ipod/i.test(navigator.userAgent);
    alert(isIOS
      ? "Safari mein Share button dabayein, phir Add to Home Screen chunein."
      : "Chrome menu (⋮) kholein aur Install app ya Add to Home screen chunein.");
  });
  function dismiss() {
    if (mode === "install") localStorage.setItem(KEY,"1");
    dock.hidden = true;
  }
  later.addEventListener("click", dismiss);
  close.addEventListener("click", dismiss);

  window.addEventListener("beforeinstallprompt", event => {
    event.preventDefault();
    installPrompt = event;
    if (!window.matchMedia("(display-mode: standalone)").matches) showInstall();
  });
  window.addEventListener("appinstalled", () => { installPrompt = null; dock.hidden = true; });

  if ("serviceWorker" in navigator && location.protocol === "https:") {
    navigator.serviceWorker.register(url("service-worker.js"), {scope:base.pathname})
      .then(async reg => {
        registration = reg;
        await checkWaitingWorker();
        reg.addEventListener("updatefound", () => {
          const worker = reg.installing;
          if (!worker) return;
          worker.addEventListener("statechange", async () => {
            if (worker.state === "installed" && navigator.serviceWorker.controller) showUpdate(await readRelease());
          });
        });
        reg.update().catch(() => {});
        setInterval(() => reg.update().catch(() => {}), 6 * 60 * 60 * 1000);
      })
      .catch(error => console.warn("PaySaathi app update check:", error));
  }

  setTimeout(showInstall, 1800);
})();