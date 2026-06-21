import { h, money, num, sheet, loading } from "../ui.js";
import { dashboardStats, recentSales, getSaleDetail, listSales, listPurchases, getPurchaseDetail, monthlyReport } from "../db.js";

export async function renderDashboard(view) {
  view.append(loading());
  const [stats, sales, report] = await Promise.all([dashboardStats(), recentSales(10), monthlyReport()]);
  view.innerHTML = "";

  view.append(
    h("div", { class: "stat-grid" }, [
      stat(money(stats.todayTotal), "今日營業額"),
      stat(stats.todayOrders, "今日單數"),
      stat(money(report.revenue), "本月營業額"),
      stat(money(report.profit), "本月毛利（估）"),
    ])
  );
  view.append(
    h("div", { class: "stat-grid" }, [
      stat(stats.productCount, "商品種類"),
      stat(money(stats.inventoryValue), "庫存成本估值"),
    ])
  );

  // 本月熱銷
  view.append(h("div", { class: "section-title" }, "本月熱銷 Top 5"));
  if (!report.top.length) {
    view.append(h("div", { class: "card", style: "color:var(--muted)" }, "本月尚無銷售"));
  } else {
    report.top.forEach((t, i) => view.append(h("div", { class: "list-item" }, [
      h("div", { class: "price", style: "min-width:26px;text-align:center" }, String(i + 1)),
      h("div", { class: "grow" }, [h("div", { class: "title" }, t.name), h("div", { class: "sub" }, `售出 ${num(t.qty)} · ${money(t.amount)}`)]),
    ])));
  }

  // 紀錄查詢
  view.append(h("div", { class: "section-title" }, "紀錄查詢"));
  view.append(h("div", { class: "row" }, [
    h("button", { class: "btn", onclick: showPurchaseHistory }, "📥 進貨紀錄"),
    h("button", { class: "btn", onclick: showSalesHistory }, "🧾 銷售紀錄"),
  ]));

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
      view.append(h("div", { class: "list-item", onclick: () => showSaleDetail(s) }, [
        h("div", { class: "grow" }, [
          h("div", { class: "title" }, money(s.total)),
          h("div", { class: "sub" }, `${fmt(s.sale_date)} · ${s.payment_method || ""}`),
        ]),
        h("div", { class: "sub" }, "明細 ›"),
      ]));
    }
  }
}

function detailRow(name, sub, amount) {
  return h("div", { class: "list-item" }, [
    h("div", { class: "grow" }, [h("div", { class: "title" }, name), h("div", { class: "sub" }, sub)]),
    h("div", { class: "price" }, amount),
  ]);
}

async function showSalesHistory() {
  let sales = [];
  try { sales = await listSales(); } catch {}
  sheet(`銷售紀錄（${sales.length}）`, () =>
    sales.length
      ? h("div", {}, sales.map((s) => h("div", { class: "list-item", onclick: () => showSaleDetail(s) }, [
          h("div", { class: "grow" }, [h("div", { class: "title" }, money(s.total)), h("div", { class: "sub" }, `${fmt(s.sale_date)} · ${s.payment_method || ""}`)]),
          h("div", { class: "sub" }, "明細 ›"),
        ])))
      : h("div", { class: "empty" }, "還沒有銷售紀錄"));
}

async function showPurchaseHistory() {
  let purchases = [];
  try { purchases = await listPurchases(); } catch {}
  sheet(`進貨紀錄（${purchases.length}）`, () =>
    purchases.length
      ? h("div", {}, purchases.map((p) => h("div", { class: "list-item", onclick: () => showPurchaseDetail(p) }, [
          h("div", { class: "grow" }, [h("div", { class: "title" }, money(p.total)), h("div", { class: "sub" }, `${p.purchase_date} · ${p.suppliers?.name || "未指定廠商"}`)]),
          h("div", { class: "sub" }, "明細 ›"),
        ])))
      : h("div", { class: "empty" }, "還沒有進貨紀錄"));
}

async function showPurchaseDetail(p) {
  let items = [];
  try { items = await getPurchaseDetail(p.id); } catch {}
  sheet("進貨明細", () => h("div", {}, [
    h("p", { class: "section-title" }, `${p.purchase_date} · ${p.suppliers?.name || "未指定廠商"}`),
    ...(items.length ? items.map((it) => detailRow(it.products?.name || "商品", `${num(it.qty)} ${it.products?.unit || ""} × ${money(it.unit_cost)}`, money(it.subtotal))) : [h("div", { class: "empty" }, "無明細")]),
    h("div", { class: "list-item", style: "font-weight:800" }, [h("div", { class: "grow" }, "進貨總額"), h("div", { class: "price", style: "font-size:20px" }, money(p.total))]),
  ]));
}

async function showSaleDetail(s) {
  let items = [];
  try { items = await getSaleDetail(s.id); } catch {}
  sheet("銷售明細", () => h("div", {}, [
    h("p", { class: "section-title" }, `${fmt(s.sale_date)} · ${s.payment_method || ""}`),
    ...(items.length ? items.map((it) => h("div", { class: "list-item" }, [
      h("div", { class: "grow" }, [
        h("div", { class: "title" }, it.products?.name || "商品"),
        h("div", { class: "sub" }, `${num(it.qty)} ${it.products?.unit || ""} × ${money(it.unit_price)}`),
      ]),
      h("div", { class: "price" }, money(it.subtotal)),
    ])) : [h("div", { class: "empty" }, "無明細")]),
    h("div", { class: "list-item", style: "font-weight:800" }, [
      h("div", { class: "grow" }, "合計"),
      h("div", { class: "price", style: "font-size:20px" }, money(s.total)),
    ]),
  ]));
}

function stat(num_, lbl) {
  return h("div", { class: "stat" }, [h("div", { class: "num" }, num_), h("div", { class: "lbl" }, lbl)]);
}

function fmt(iso) {
  const d = new Date(iso);
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getMonth() + 1}/${d.getDate()} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
