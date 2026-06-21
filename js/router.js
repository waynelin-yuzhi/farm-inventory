import { renderDashboard } from "./views/dashboard.js";
import { renderCheckout } from "./views/checkout.js";
import { renderPurchase } from "./views/purchase.js";
import { renderProducts } from "./views/products.js";
import { renderSuppliers } from "./views/suppliers.js";

const routes = {
  dashboard: { title: "總覽", render: renderDashboard },
  checkout: { title: "結帳", render: renderCheckout },
  purchase: { title: "進貨", render: renderPurchase },
  products: { title: "庫存", render: renderProducts },
  suppliers: { title: "廠商", render: renderSuppliers },
};

export function startRouter() {
  window.addEventListener("hashchange", handleRoute);
  if (!location.hash) location.hash = "#/dashboard";
  else handleRoute();
}

async function handleRoute() {
  const key = (location.hash.replace(/^#\//, "") || "dashboard").split("/")[0];
  const route = routes[key] || routes.dashboard;

  document.getElementById("page-title").textContent = route.title;
  document.querySelectorAll(".tab").forEach((t) =>
    t.classList.toggle("active", t.dataset.tab === key)
  );

  const view = document.getElementById("view");
  view.innerHTML = "";
  view.scrollTop = 0;
  try {
    await route.render(view);
  } catch (err) {
    console.error(err);
    view.innerHTML = `<div class="empty">發生錯誤：${err.message || err}</div>`;
  }
}
