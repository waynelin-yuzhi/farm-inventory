import { h, money, num, toast, sheet, field, confirmDialog, loading } from "../ui.js";
import { listProducts, upsertProduct, deleteProduct, adjustStock } from "../db.js";
import { scanBarcode } from "../scanner.js";
import { allCategories, addCategory, mergeCategories } from "../categories.js";

let activeCat = "";

export async function renderProducts(view) {
  view.append(loading());
  await refresh(view);
}

async function refresh(view, search = "") {
  const products = await listProducts(search);
  mergeCategories(products.map((p) => p.category));
  view.innerHTML = "";

  const searchBox = h("input", {
    placeholder: "搜尋名稱 / 條碼 / 分類", value: search,
    style: "width:100%;padding:12px;border:1px solid var(--border);border-radius:11px;margin-bottom:12px;background:#fafbfa;",
    oninput: debounce((e) => refresh(view, e.target.value), 300),
  });
  view.append(searchBox);

  // 分類筛选 chips（全部 + 各分類）
  const cats = ["", ...allCategories()];
  view.append(h("div", { class: "chips" }, cats.map((c) => h("div", {
    class: "chip" + (activeCat === c ? " active" : ""),
    onclick: () => { activeCat = c; refresh(view, search); },
  }, c === "" ? "全部" : c))));

  const list = activeCat ? products.filter((p) => (p.category || "") === activeCat) : products;

  view.append(h("div", { class: "section-title" }, `庫存（${list.length} 項）${activeCat ? " · " + activeCat : ""}`));
  view.append(h("p", { class: "section-title", style: "margin:-4px 4px 8px;color:var(--muted);font-weight:400" },
    "新增商品請到「進貨」掃碼或按「＋ 新增商品」"));

  if (!list.length) {
    view.append(h("div", { class: "empty" }, (search || activeCat) ? "此條件查無商品" : "還沒有商品，到「進貨」建立第一筆"));
    return;
  }
  for (const p of list) view.append(productItem(view, p));
}

function productItem(view, p) {
  const stockNum = Number(p.stock);
  const isLow = Number(p.reorder_level) > 0 && stockNum <= Number(p.reorder_level);
  let tag = null, stockColor = "var(--green-d)";
  if (stockNum <= 0) { tag = h("span", { class: "tag out" }, "缺貨"); stockColor = "var(--danger)"; }
  else if (isLow) { tag = h("span", { class: "tag low" }, "偏低"); stockColor = "var(--warn)"; }

  return h("div", { class: "list-item", onclick: () => editProduct(view, p) }, [
    h("div", { class: "grow" }, [
      h("div", { class: "title" }, [p.name, tag]),
      h("div", { class: "sub" }, `${p.barcode || "無條碼"}${p.category ? " · " + p.category : ""} · 售價 ${money(p.sale_price)}`),
    ]),
    h("div", { style: "text-align:right;white-space:nowrap" }, [
      h("div", { class: "price", style: `color:${stockColor};font-size:18px` }, `${num(p.stock)} ${p.unit}`),
      h("div", { class: "sub" }, "庫存"),
    ]),
  ]);
}

// 供「進貨 / 結帳」掃到新商品或手動新增時呼叫；onSaved(saved) 在儲存成功後回呼
export function editProduct(view, p, onSaved) {
  p = p || {};
  const isNew = !p.id;
  const get = {};
  const inp = (key, attrs) => (get[key] = h("input", { value: p[key] ?? attrs?.value ?? "", ...attrs }));

  sheet(isNew ? "新增商品" : "編輯商品", (close) => {
    const barcodeInput = h("input", { value: p.barcode ?? "", placeholder: "可空白" });
    get.barcode = barcodeInput;

    // 分類下拉：预设六类 + 已用过的，最后一项「＋ 新增分類…」可直接打新的
    if (p.category) addCategory(p.category);
    const catSel = h("select", {}, [
      h("option", { value: "" }, "（不分類）"),
      ...allCategories().map((c) => h("option", { value: c }, c)),
      h("option", { value: "__new__" }, "＋ 新增分類…"),
    ]);
    catSel.value = p.category || "";
    catSel.addEventListener("change", () => {
      if (catSel.value === "__new__") {
        const v = (window.prompt("輸入新分類名稱：") || "").trim();
        if (v) { addCategory(v); catSel.insertBefore(h("option", { value: v }, v), catSel.lastChild); catSel.value = v; }
        else catSel.value = p.category || "";
      }
    });
    get.category = catSel;

    const body = h("div", {}, [
      isNew && p._source && h("p", { class: "section-title", style: "color:var(--green-d)" }, p._source),
      field("條碼", h("div", { class: "row" }, [
        barcodeInput,
        h("button", { class: "btn btn-sm", style: "flex:0 0 auto", onclick: async () => {
          const c = await scanBarcode(); if (c) barcodeInput.value = c;
        } }, "📷 掃"),
      ])),
      field("名稱 *", inp("name", { placeholder: "如：高麗菜" })),
      p._wantCost
        ? h("div", { class: "row" }, [
            field("售價 *", inp("sale_price", { type: "number", inputmode: "decimal", placeholder: "0" })),
            field("進貨成本", inp("cost", { type: "number", inputmode: "decimal", placeholder: "本次進貨單價" })),
          ])
        : field("售價 *", inp("sale_price", { type: "number", inputmode: "decimal", placeholder: "0" })),
      h("div", { class: "row" }, [
        field("單位", inp("unit", { value: p.unit || "件", placeholder: "件/台斤/公斤" })),
        field("分類", catSel),
      ]),
      field("庫存預警線", inp("reorder_level", { type: "number", inputmode: "decimal", value: p.reorder_level || "", placeholder: "低於此值提醒，0=不提醒" })),
      field("備註", inp("note", { placeholder: "可空白" })),
      !isNew && field(`目前庫存（${p.unit}）— 盤點可直接修改`, inp("stock", { type: "number", inputmode: "decimal", value: num(p.stock) })),
      !isNew && h("p", { class: "section-title", style: "margin-top:-4px;color:var(--muted);font-weight:400" }, "平時由「進貨」加、「結帳」扣；這裡直接改＝盤點修正"),
      h("button", { class: "btn btn-primary btn-block", onclick: async () => {
        if (!get.name.value.trim()) return toast("請填寫名稱", "err");
        try {
          const saved = await upsertProduct({
            id: p.id, barcode: get.barcode.value.trim(), name: get.name.value.trim(),
            sale_price: get.sale_price.value, unit: get.unit.value.trim() || "件",
            category: (get.category.value === "__new__" ? "" : get.category.value).trim(),
            reorder_level: get.reorder_level.value, note: get.note.value.trim(),
          });
          // 盘点：库存若被直接改动，记一笔调整
          if (!isNew && get.stock) {
            const newStock = Number(get.stock.value);
            if (!Number.isNaN(newStock) && newStock !== Number(p.stock)) await adjustStock(p.id, newStock);
          }
          // 进货情境下顺手填的成本，回传给呼叫端带进明细
          const meta = {};
          if (get.cost && get.cost.value !== "") meta.cost = Math.max(0, Number(get.cost.value) || 0);
          toast("已儲存", "ok"); close();
          if (onSaved) onSaved(saved, meta); else refresh(view);
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
