import { h, money, num, toast, sheet, field, loading } from "../ui.js";
import { getProductByBarcode, listProducts, listSuppliers, createPurchase } from "../db.js";
import { scanBarcode } from "../scanner.js";
import { editProduct } from "./products.js";

// 进货单草稿：product_id -> { product, qty, unit_cost }
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

  // 单头：厂商 + 日期
  const supplierSel = h("select", { onchange: (e) => (supplierId = e.target.value) },
    [h("option", { value: "" }, "（不指定厂商）"), ...suppliers.map((s) => h("option", { value: s.id }, s.name))]);
  supplierSel.value = supplierId;
  const dateInput = h("input", { type: "date", value: purchaseDate, onchange: (e) => (purchaseDate = e.target.value) });

  view.append(h("div", { class: "card" }, [
    h("div", { class: "row" }, [field("厂商", supplierSel), field("进货日期", dateInput)]),
  ]));

  view.append(h("div", { class: "section-title" }, "进货明细"));

  if (draft.size === 0) {
    view.append(h("div", { class: "empty" }, "扫码或搜索添加要进货的商品，并填写数量与成本"));
  } else {
    for (const item of draft.values()) view.append(purchaseLine(view, item, suppliers));
  }

  view.append(h("div", { class: "row", style: "margin-top:6px" }, [
    h("button", { class: "btn", onclick: () => searchAdd(view, suppliers) }, "🔎 搜索添加"),
    draft.size > 0 && h("button", { class: "btn", onclick: () => { draft.clear(); paint(view, suppliers); } }, "🗑 清空"),
  ]));

  view.append(h("button", { class: "fab-scan", onclick: () => scanAdd(view, suppliers) }, ["📷 扫码进货"]));

  const total = draftTotal();
  view.append(h("div", { class: "bottom-bar" }, [
    h("div", { class: "total" }, money(total)),
    h("button", { class: "btn btn-primary", disabled: draft.size === 0, onclick: () => submit(view, suppliers) }, "入库"),
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
        h("div", { class: "sub" }, `当前库存 ${num(item.product.stock)} ${item.product.unit} · 上次成本 ${money(item.product.last_cost)}`),
      ]),
      h("button", { class: "btn btn-sm", onclick: () => { draft.delete(item.product.id); paint(view); } }, "移除"),
    ]),
    h("div", { class: "row" }, [
      field(`数量(${item.product.unit})`, qty),
      field("进货单价", cost),
      h("div", { class: "field" }, [h("label", {}, "小计"), h("div", { class: "price", style: "padding-top:10px", id: "sub-" + item.product.id }, money(lineSubtotal(item)))]),
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
  if (draft.has(product.id)) { toast("已在明细中", ""); return; }
  draft.set(product.id, { product, qty: 1, unit_cost: Number(product.last_cost) || "" });
}

async function scanAdd(view, suppliers) {
  const code = await scanBarcode();
  if (!code) return;
  const p = await getProductByBarcode(code);
  if (!p) {
    toast("新条码，请先建档", "");
    editProduct(view, { barcode: code }, (saved) => {
      addToDraft(saved);
      paint(view, suppliers);
      toast(`已建档并加入：${saved.name}`, "ok");
    });
    return;
  }
  addToDraft(p);
  paint(view, suppliers);
}

async function searchAdd(view, suppliers) {
  const products = await listProducts();
  sheet("选择商品", (close) => {
    const box = h("input", { placeholder: "输入名称筛选", style: "width:100%;padding:12px;border:1px solid var(--border);border-radius:11px;margin-bottom:10px;background:#fafbfa" });
    const list = h("div", {});
    const draw = (kw = "") => {
      list.innerHTML = "";
      const filtered = products.filter((p) => !kw || (p.name + (p.barcode || "")).toLowerCase().includes(kw.toLowerCase()));
      if (!filtered.length) { list.append(h("div", { class: "empty" }, "无匹配")); return; }
      for (const p of filtered) {
        list.append(h("div", { class: "list-item", onclick: () => { addToDraft(p); close(); paint(view, suppliers); } }, [
          h("div", { class: "grow" }, [h("div", { class: "title" }, p.name), h("div", { class: "sub" }, `库存 ${num(p.stock)} ${p.unit}`)]),
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
    if (!i.qty || i.qty <= 0) return toast(`「${i.product.name}」数量需大于 0`, "err");
    if (i.unit_cost === "" || i.unit_cost < 0) return toast(`「${i.product.name}」请填写成本`, "err");
    items.push({ product_id: i.product.id, qty: Number(i.qty), unit_cost: Number(i.unit_cost) });
  }
  try {
    await createPurchase({ supplier_id: supplierId, date: purchaseDate, note: null, items });
    toast("已入库，库存与成本已更新", "ok");
    draft.clear();
    paint(view, suppliers);
  } catch (e) { toast(e.message || "入库失败", "err"); }
}

function todayStr() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}
