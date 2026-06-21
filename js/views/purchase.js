import { h, money, num, toast, sheet, field, loading, progressToast } from "../ui.js";
import { getProductByBarcode, listProducts, listSuppliers, createPurchase } from "../db.js";
import { scanBarcode } from "../scanner.js";
import { editProduct } from "./products.js";
import { lookupBarcode } from "../lookup.js";

// 進貨單草稿：product_id -> { product, qty, unit_cost }
let draft = new Map();
let supplierId = "";
let purchaseDate = todayStr();

export async function renderPurchase(view) {
  view.innerHTML = "";
  view.append(loading());
  const suppliers = await listSuppliers();
  paint(view, suppliers);
}

function paint(view, suppliers) {
  view.innerHTML = "";

  // 單頭：廠商 + 日期
  const supplierSel = h("select", { onchange: (e) => (supplierId = e.target.value) },
    [h("option", { value: "" }, "（不指定廠商）"), ...suppliers.map((s) => h("option", { value: s.id }, s.name))]);
  supplierSel.value = supplierId;
  const dateInput = h("input", { type: "date", value: purchaseDate, onchange: (e) => (purchaseDate = e.target.value) });

  view.append(h("div", { class: "card" }, [
    h("div", { class: "row" }, [field("廠商", supplierSel), field("進貨日期", dateInput)]),
  ]));

  view.append(h("div", { class: "section-title" }, "進貨明細"));

  if (draft.size === 0) {
    view.append(h("div", { class: "empty" }, "掃碼或搜尋加入要進貨的商品，並填寫數量與成本"));
  } else {
    for (const item of draft.values()) view.append(purchaseLine(view, item, suppliers));
  }

  view.append(h("div", { class: "row", style: "margin-top:6px" }, [
    h("button", { class: "btn", onclick: () => searchAdd(view, suppliers) }, "🔎 搜尋加入"),
    h("button", { class: "btn", onclick: () => manualAdd(view, suppliers) }, "＋ 新增商品"),
  ]));
  if (draft.size > 0) {
    view.append(h("button", { class: "btn btn-block", style: "margin-top:8px", onclick: () => { draft.clear(); paint(view, suppliers); } }, "🗑 清空"));
  }

  // 底部留白，避免最後一項被結算列遮住
  view.append(h("div", { style: "height:84px" }));

  // 右下角懸浮掃碼鈕（浮在結算列上方，不被遮住）
  view.append(h("button", { class: "fab-scan with-bar", onclick: () => scanAdd(view, suppliers) }, ["📷 掃碼"]));

  // 底部結算列
  const total = draftTotal();
  view.append(h("div", { class: "bottom-bar" }, [
    h("div", { class: "total" }, money(total)),
    h("button", { class: "btn btn-primary", style: "flex:0 0 auto", disabled: draft.size === 0, onclick: () => submit(view, suppliers) }, "入庫"),
  ]));
}

function purchaseLine(view, item) {
  const qty = h("input", { type: "number", inputmode: "decimal", value: num(item.qty),
    onchange: (e) => { item.qty = Math.max(0, Number(e.target.value) || 0); recompute(view, item); } });
  const cost = h("input", { type: "number", inputmode: "decimal", value: item.unit_cost === "" ? "" : num(item.unit_cost), placeholder: "成本",
    onchange: (e) => { item.unit_cost = e.target.value === "" ? "" : Math.max(0, Number(e.target.value) || 0); recompute(view, item); } });

  return h("div", { class: "card", id: "pl-" + item.product.id }, [
    h("div", { class: "list-item", style: "box-shadow:none;padding:0;margin:0 0 8px;background:none" }, [
      h("div", { class: "grow" }, [
        h("div", { class: "title" }, item.product.name),
        h("div", { class: "sub" }, `目前庫存 ${num(item.product.stock)} ${item.product.unit} · 上次成本 ${money(item.product.last_cost)}`),
      ]),
      h("button", { class: "btn btn-sm", onclick: () => { draft.delete(item.product.id); paint(view); } }, "移除"),
    ]),
    h("div", { class: "row" }, [
      field(`數量(${item.product.unit})`, qty),
      field("進貨單價", cost),
      h("div", { class: "field" }, [h("label", {}, "小計"), h("div", { class: "price", style: "padding-top:10px", id: "sub-" + item.product.id }, money(lineSubtotal(item)))]),
    ]),
  ]);
}

function recompute(view, item) {
  const subEl = document.getElementById("sub-" + item.product.id);
  if (subEl) subEl.textContent = money(lineSubtotal(item));
  const totalEl = document.querySelector(".bottom-bar .total");
  if (totalEl) totalEl.textContent = money(draftTotal());
}

const lineSubtotal = (i) => Number(i.qty || 0) * Number(i.unit_cost || 0);
function draftTotal() { let t = 0; for (const i of draft.values()) t += lineSubtotal(i); return t; }

function addToDraft(product) {
  if (draft.has(product.id)) { toast("已在明細中", ""); return; }
  draft.set(product.id, { product, qty: 1, unit_cost: Number(product.last_cost) || "" });
}

async function scanAdd(view, suppliers) {
  const code = await scanBarcode();
  if (!code) return;

  // 阶段1：查本店库存
  const prog = progressToast("① 查詢本店庫存…");
  const p = await getProductByBarcode(code);
  if (p) {
    prog.done(`本店已有：${p.name}，已加入明細`, "ok");
    addToDraft(p);
    paint(view, suppliers);
    return;
  }

  // 阶段2：本店没有 → 查免费公开资料库
  prog.update("② 本店無此商品，查詢公開資料庫…");
  const info = await lookupBarcode(code);
  if (info) prog.done(`③ 公開資料庫帶入：${info.name}`, "ok");
  else prog.done("③ 公開資料庫查不到，請手動填寫名稱", "err");

  editProduct(view, {
    barcode: code, name: info?.name || "", category: info?.category || "",
    _source: info ? "📥 名稱來自公開資料庫，可修改" : "✍️ 公開資料庫查不到，請手動填寫",
  }, (saved) => {
    addToDraft(saved);
    paint(view, suppliers);
    toast(`已建檔並加入：${saved.name}`, "ok");
  });
}

// 手動新增商品（沒有條碼的農產品用這個），建檔後直接進明細
function manualAdd(view, suppliers) {
  editProduct(view, { _source: "✍️ 手動新增（無條碼商品）" }, (saved) => {
    addToDraft(saved);
    paint(view, suppliers);
    toast(`已建檔並加入：${saved.name}`, "ok");
  });
}

async function searchAdd(view, suppliers) {
  const products = await listProducts();
  sheet("選擇商品", (close) => {
    const box = h("input", { placeholder: "輸入名稱篩選", style: "width:100%;padding:12px;border:1px solid var(--border);border-radius:11px;margin-bottom:10px;background:#fafbfa" });
    const list = h("div", {});
    const draw = (kw = "") => {
      list.innerHTML = "";
      const filtered = products.filter((p) => !kw || (p.name + (p.barcode || "")).toLowerCase().includes(kw.toLowerCase()));
      if (!filtered.length) { list.append(h("div", { class: "empty" }, "無符合")); return; }
      for (const p of filtered) {
        list.append(h("div", { class: "list-item", onclick: () => { addToDraft(p); close(); paint(view, suppliers); } }, [
          h("div", { class: "grow" }, [h("div", { class: "title" }, p.name), h("div", { class: "sub" }, `庫存 ${num(p.stock)} ${p.unit}`)]),
        ]));
      }
    };
    box.addEventListener("input", (e) => draw(e.target.value));
    draw();
    return h("div", {}, [box, list]);
  });
}

async function submit(view, suppliers) {
  const items = [];
  for (const i of draft.values()) {
    if (!i.qty || i.qty <= 0) return toast(`「${i.product.name}」數量需大於 0`, "err");
    if (i.unit_cost === "" || i.unit_cost < 0) return toast(`「${i.product.name}」請填寫成本`, "err");
    items.push({ product_id: i.product.id, qty: Number(i.qty), unit_cost: Number(i.unit_cost) });
  }
  try {
    await createPurchase({ supplier_id: supplierId, date: purchaseDate, note: null, items });
    toast("已入庫，庫存與成本已更新", "ok");
    draft.clear();
    paint(view, suppliers);
  } catch (e) { toast(e.message || "入庫失敗", "err"); }
}

function todayStr() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}
