import { h, money, num, toast, sheet, field, confirmDialog, loading } from "../ui.js";
import { listProducts, upsertProduct, deleteProduct, getProductByBarcode } from "../db.js";
import { scanBarcode } from "../scanner.js";

export async function renderProducts(view) {
  view.append(loading());
  await refresh(view);

  view.append(
    h("button", { class: "fab-scan", onclick: () => openScanFlow(view) }, ["📷 扫码建档"])
  );
}

async function refresh(view, search = "") {
  const products = await listProducts(search);
  view.innerHTML = "";

  const searchBox = h("input", {
    class: "", placeholder: "搜索名称 / 条码 / 分类", value: search,
    style: "width:100%;padding:12px;border:1px solid var(--border);border-radius:11px;margin-bottom:12px;background:#fafbfa;",
    oninput: debounce((e) => refresh(view, e.target.value), 300),
  });
  view.append(searchBox);

  view.append(
    h("button", { class: "btn btn-primary btn-block", style: "margin-bottom:14px", onclick: () => editProduct(view, null) }, "+ 新增商品")
  );

  if (!products.length) {
    view.append(h("div", { class: "empty" }, "还没有商品，点上方按钮或右下角扫码建档"));
  } else {
    for (const p of products) view.append(productItem(view, p));
  }

  view.append(h("button", { class: "fab-scan", onclick: () => openScanFlow(view) }, ["📷 扫码建档"]));
}

function productItem(view, p) {
  const stockNum = Number(p.stock);
  let tag = null;
  if (stockNum <= 0) tag = h("span", { class: "tag out" }, "缺货");
  else if (Number(p.reorder_level) > 0 && stockNum <= Number(p.reorder_level)) tag = h("span", { class: "tag low" }, "偏低");

  return h("div", { class: "list-item", onclick: () => editProduct(view, p) }, [
    h("div", { class: "grow" }, [
      h("div", { class: "title" }, [p.name, tag]),
      h("div", { class: "sub" }, `${p.barcode || "无条码"} · 库存 ${num(p.stock)} ${p.unit}${p.category ? " · " + p.category : ""}`),
    ]),
    h("div", { class: "price" }, money(p.sale_price)),
  ]);
}

async function openScanFlow(view) {
  const code = await scanBarcode();
  if (!code) return;
  const existing = await getProductByBarcode(code);
  if (existing) {
    toast("该条码已存在，打开编辑", "");
    editProduct(view, existing);
  } else {
    editProduct(view, { barcode: code });
  }
}

export function editProduct(view, p, onSaved) {
  p = p || {};
  const isNew = !p.id;
  const get = {};
  const inp = (key, attrs) => (get[key] = h("input", { value: p[key] ?? attrs?.value ?? "", ...attrs }));

  sheet(isNew ? "新增商品" : "编辑商品", (close) => {
    const barcodeInput = h("input", { value: p.barcode ?? "", placeholder: "可空" });
    get.barcode = barcodeInput;

    const body = h("div", {}, [
      field("条码", h("div", { class: "row" }, [
        barcodeInput,
        h("button", { class: "btn btn-sm", style: "flex:0 0 auto", onclick: async () => {
          const c = await scanBarcode(); if (c) barcodeInput.value = c;
        } }, "📷 扫"),
      ])),
      field("名称 *", inp("name", { placeholder: "如：有机白菜" })),
      field("售价 *", inp("sale_price", { type: "number", inputmode: "decimal", placeholder: "0.00" })),
      h("div", { class: "row" }, [
        field("单位", inp("unit", { value: p.unit || "件", placeholder: "件/斤/公斤" })),
        field("分类", inp("category", { placeholder: "蔬菜/水果" })),
      ]),
      field("库存预警线", inp("reorder_level", { type: "number", inputmode: "decimal", value: p.reorder_level || "", placeholder: "低于此值提醒，0=不提醒" })),
      field("备注", inp("note", { placeholder: "可空" })),
      !isNew && h("p", { class: "section-title" }, `当前库存：${num(p.stock)} ${p.unit}（库存请通过「进货 / 结账」变动）`),
      h("button", { class: "btn btn-primary btn-block", onclick: async () => {
        if (!get.name.value.trim()) return toast("请填写名称", "err");
        try {
          const saved = await upsertProduct({
            id: p.id, barcode: get.barcode.value.trim(), name: get.name.value.trim(),
            sale_price: get.sale_price.value, unit: get.unit.value.trim() || "件",
            category: get.category.value.trim(), reorder_level: get.reorder_level.value, note: get.note.value.trim(),
          });
          toast("已保存", "ok"); close();
          if (onSaved) onSaved(saved); else refresh(view);
        } catch (e) { toast(e.message || "保存失败", "err"); }
      } }, "保存"),
      !isNew && h("button", { class: "btn btn-danger btn-block", onclick: async () => {
        if (await confirmDialog("删除商品", `确定删除「${p.name}」？历史单据不受影响。`)) {
          try { await deleteProduct(p.id); toast("已删除", "ok"); close(); refresh(view); }
          catch (e) { toast("删除失败（可能有关联单据）", "err"); }
        }
      } }, "删除"),
    ]);
    return body;
  });
}

function debounce(fn, ms) {
  let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}
