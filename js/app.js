import { isConfigured } from "./config.js";
import { supabase } from "./supabase.js";
import { startRouter } from "./router.js";
import { toast } from "./ui.js";

const authScreen = document.getElementById("auth-screen");
const app = document.getElementById("app");
const authMsg = document.getElementById("auth-msg");

let routerStarted = false;

function showApp() {
  authScreen.classList.add("hidden");
  app.classList.remove("hidden");
  if (!routerStarted) { startRouter(); routerStarted = true; }
}
function showAuth() {
  app.classList.add("hidden");
  authScreen.classList.remove("hidden");
}

async function init() {
  if (!isConfigured()) {
    showAuth();
    authMsg.innerHTML = "⚠️ 尚未設定 Supabase。<br>請在 <code>js/config.js</code> 填入專案 URL 與 anon key。";
    document.getElementById("auth-login").disabled = true;
    document.getElementById("auth-signup").disabled = true;
    return;
  }

  const { data } = await supabase.auth.getSession();
  if (data.session) showApp();
  else showAuth();

  supabase.auth.onAuthStateChange((_e, session) => {
    if (session) showApp(); else showAuth();
  });
}

// ---- 登录 / 注册 ----
const emailEl = document.getElementById("auth-email");
const pwEl = document.getElementById("auth-password");

document.getElementById("auth-login").addEventListener("click", async () => {
  authMsg.textContent = "";
  const { error } = await supabase.auth.signInWithPassword({ email: emailEl.value.trim(), password: pwEl.value });
  if (error) authMsg.textContent = "登入失敗：" + error.message;
});

document.getElementById("auth-signup").addEventListener("click", async () => {
  authMsg.textContent = "";
  if (pwEl.value.length < 6) { authMsg.textContent = "密碼至少 6 位"; return; }
  const { data, error } = await supabase.auth.signUp({ email: emailEl.value.trim(), password: pwEl.value });
  if (error) { authMsg.textContent = "註冊失敗：" + error.message; return; }
  if (data.session) toast("註冊成功", "ok");
  else authMsg.textContent = "註冊成功，請收信完成驗證後登入（或在 Supabase 關閉信箱驗證）。";
});

document.getElementById("logout-btn").addEventListener("click", async () => {
  await supabase.auth.signOut();
});

// ---- PWA Service Worker ----
if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => navigator.serviceWorker.register("./sw.js").catch(() => {}));
}

init();
