import { h, money, num, loading } from "../ui.js";
import { dashboardStats, recentSales } from "../db.js";

export async function renderDashboard(view) {
  view.append(loading());
  const [stats, sales] = await Promise.all([dashboardStats(), recentSales(10)]);
  view.innerHTML = "";

  view.append(
    h("div", { class: "stat-grid" }, [
      stat(money(stats.todayTotal), "今日營業額"),
      stat(stats.todayOrders, "今日單數"),
      stat(stats.productCount, "商品種類"),
      stat(money(stats.inventoryValue), "庫存成本估值"),
    ])
  );

  // 低庫存提醒
  view.append(h("div", { class: "section-title" }, `庫存預警（${stats.low.length}）`));
  if (!stats.low.length) {
    view.append(h("div", { class: "card", style: "color:var(--muted)" }, "目前沒有低於預警線的商品 👍"));
  } else {
    for (const p of stats.low) {
      view.append(h("div", { class: "list-item" }, [
        h("div", { class: "grow" }, [
          h("div", { class: "title" }, [p.name, h("span", { class: "tag " + (Number(p.stock) <= 0 ? "out" : "low") }, Number(p.stock) <= 0 ? "缺貨" : "偏低")]),
          h("div", { class: "sub" }, `庫存 ${num(p.stock)} / 預警線 ${num(p.reorder_level)} ${p.unit}`),
        ]),
      ]));
    }
  }

  // 最近銷售
  view.append(h("div", { class: "section-title" }, "最近銷售"));
  if (!sales.length) {
    view.append(h("div", { class: "card", style: "color:var(--muted)" }, "還沒有銷售紀錄"));
  } else {
    for (const s of sales) {
      view.append(h("div", { class: "list-item" }, [
        h("div", { class: "grow" }, [
          h("div", { class: "title" }, money(s.total)),
          h("div", { class: "sub" }, `${fmt(s.sale_date)} · ${s.payment_method || ""}`),
        ]),
      ]));
    }
  }
}

function stat(num_, lbl) {
  return h("div", { class: "stat" }, [h("div", { class: "num" }, num_), h("div", { class: "lbl" }, lbl)]);
}

function fmt(iso) {
  const d = new Date(iso);
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getMonth() + 1}/${d.getDate()} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
