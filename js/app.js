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
    authMsg.innerHTML = "⚠️ 还没配置 Supabase。<br>请在 <code>js/config.js</code> 填入项目 URL 与 anon key。";
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
  if (error) authMsg.textContent = "登录失败：" + error.message;
});

document.getElementById("auth-signup").addEventListener("click", async () => {
  authMsg.textContent = "";
  if (pwEl.value.length < 6) { authMsg.textContent = "密码至少 6 位"; return; }
  const { data, error } = await supabase.auth.signUp({ email: emailEl.value.trim(), password: pwEl.value });
  if (error) { authMsg.textContent = "注册失败：" + error.message; return; }
  if (data.session) toast("注册成功", "ok");
  else authMsg.textContent = "注册成功，请查收邮箱完成验证后登录（或在 Supabase 关闭邮箱验证）。";
});

document.getElementById("logout-btn").addEventListener("click", async () => {
  await supabase.auth.signOut();
});

// ---- PWA Service Worker ----
if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => navigator.serviceWorker.register("./sw.js").catch(() => {}));
}

init();
