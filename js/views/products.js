import { h, money, num, toast, sheet, field, confirmDialog, loading } from "../ui.js";
import { listProducts, upsertProduct, deleteProduct, getProductByBarcode } from "../db.js";
import { scanBarcode } from "../scanner.js";
import { lookupBarcode } from "../lookup.js";

export async function renderProducts(view) {
  view.append(loading());
  await refresh(view);
}

async function refresh(view, search = "") {
  const products = await listProducts(search);
  view.innerHTML = "";

  const searchBox = h("input", {
    class: "", placeholder: "搜尋名稱 / 條碼 / 分類", value: search,
    style: "width:100%;padding:12px;border:1px solid var(--border);border-radius:11px;margin-bottom:12px;background:#fafbfa;",
    oninput: debounce((e) => refresh(view, e.target.value), 300),
  });
  view.append(searchBox);

  view.append(
    h("button", { class: "btn btn-primary btn-block", style: "margin-bottom:14px", onclick: () => editProduct(view, null) }, "+ 新增商品")
  );

  if (!products.length) {
    view.append(h("div", { class: "empty" }, "還沒有商品，點上方按鈕或右下角掃碼建檔"));
  } else {
    for (const p of products) view.append(productItem(view, p));
  }

  view.append(h("button", { class: "fab-scan", onclick: () => openScanFlow(view) }, ["📷 掃碼建檔"]));
}

function productItem(view, p) {
  const stockNum = Number(p.stock);
  let tag = null;
  if (stockNum <= 0) tag = h("span", { class: "tag out" }, "缺貨");
  else if (Number(p.reorder_level) > 0 && stockNum <= Number(p.reorder_level)) tag = h("span", { class: "tag low" }, "偏低");

  return h("div", { class: "list-item", onclick: () => editProduct(view, p) }, [
    h("div", { class: "grow" }, [
      h("div", { class: "title" }, [p.name, tag]),
      h("div", { class: "sub" }, `${p.barcode || "無條碼"} · 庫存 ${num(p.stock)} ${p.unit}${p.category ? " · " + p.category : ""}`),
    ]),
    h("div", { class: "price" }, money(p.sale_price)),
  ]);
}

async function openScanFlow(view) {
  const code = await scanBarcode();
  if (!code) return;
  const existing = await getProductByBarcode(code);
  if (existing) {
    toast("此條碼已存在，開啟編輯", "");
    editProduct(view, existing);
    return;
  }
  toast("查詢公開資料庫…", "");
  const info = await lookupBarcode(code);
  if (info) toast(`已帶入：${info.name}`, "ok");
  editProduct(view, { barcode: code, name: info?.name || "", category: info?.category || "" });
}

export function editProduct(view, p, onSaved) {
  p = p || {};
  const isNew = !p.id;
  const get = {};
  const inp = (key, attrs) => (get[key] = h("input", { value: p[key] ?? attrs?.value ?? "", ...attrs }));

  sheet(isNew ? "新增商品" : "編輯商品", (close) => {
    const barcodeInput = h("input", { value: p.barcode ?? "", placeholder: "可空白" });
    get.barcode = barcodeInput;

    const body = h("div", {}, [
      field("條碼", h("div", { class: "row" }, [
        barcodeInput,
        h("button", { class: "btn btn-sm", style: "flex:0 0 auto", onclick: async () => {
          const c = await scanBarcode(); if (c) barcodeInput.value = c;
        } }, "📷 掃"),
      ])),
      field("名稱 *", inp("name", { placeholder: "如：高麗菜" })),
      field("售價 *", inp("sale_price", { type: "number", inputmode: "decimal", placeholder: "0" })),
      h("div", { class: "row" }, [
        field("單位", inp("unit", { value: p.unit || "件", placeholder: "件/台斤/公斤" })),
        field("分類", inp("category", { placeholder: "蔬菜/水果" })),
      ]),
      field("庫存預警線", inp("reorder_level", { type: "number", inputmode: "decimal", value: p.reorder_level || "", placeholder: "低於此值提醒，0=不提醒" })),
      field("備註", inp("note", { placeholder: "可空白" })),
      !isNew && h("p", { class: "section-title" }, `目前庫存：${num(p.stock)} ${p.unit}（庫存請透過「進貨 / 結帳」變動）`),
      h("button", { class: "btn btn-primary btn-block", onclick: async () => {
        if (!get.name.value.trim()) return toast("請填寫名稱", "err");
        try {
          const saved = await upsertProduct({
            id: p.id, barcode: get.barcode.value.trim(), name: get.name.value.trim(),
            sale_price: get.sale_price.value, unit: get.unit.value.trim() || "件",
            category: get.category.value.trim(), reorder_level: get.reorder_level.value, note: get.note.value.trim(),
          });
          toast("已儲存", "ok"); close();
          if (onSaved) onSaved(saved); else refresh(view);
        } catch (e) { toast(e.message || "儲存失敗", "err"); }
      } }, "儲存"),
      !isNew && h("button", { class: "btn btn-danger btn-block", onclick: async () => {
        if (await confirmDialog("刪除商品", `確定刪除「${p.name}」？歷史單據不受影響。`)) {
          try { await deleteProduct(p.id); toast("已刪除", "ok"); close(); refresh(view); }
          catch (e) { toast("刪除失敗（可能有關聯單據）", "err"); }
        }
      } }, "刪除"),
    ]);
    return body;
  });
}

function debounce(fn, ms) {
  let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}
