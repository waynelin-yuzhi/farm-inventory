import { h, money, num, toast, sheet, field, loading, progressToast } from "../ui.js";
import { getProductByBarcode, listProducts, createSale } from "../db.js";
import { scanBarcode } from "../scanner.js";
import { editProduct } from "./products.js";
import { lookupBarcode } from "../lookup.js";

// 購物車：product_id -> { product, qty, unit_price }
let cart = new Map();

export async function renderCheckout(view) {
  view.innerHTML = "";
  paint(view);
}

function paint(view) {
  view.innerHTML = "";

  const lines = h("div", {});
  if (cart.size === 0) {
    lines.append(h("div", { class: "empty" }, "購物車空 — 掃碼或搜尋把商品加進來"));
  } else {
    for (const item of cart.values()) lines.append(cartLine(view, item));
  }
  view.append(lines);

  // 操作按鈕
  view.append(
    h("div", { class: "row", style: "margin-top:6px" }, [
      h("button", { class: "btn", onclick: () => searchAdd(view) }, "🔎 搜尋加入"),
      cart.size > 0 && h("button", { class: "btn", onclick: () => { cart.clear(); paint(view); } }, "🗑 清空"),
    ])
  );

  // 底部留白，避免最後一項被結算列遮住
  view.append(h("div", { style: "height:84px" }));

  // 右下角懸浮掃碼鈕（浮在結算列上方，不被遮住）
  view.append(h("button", { class: "fab-scan with-bar", onclick: () => scanAdd(view) }, ["📷 掃碼"]));

  // 底部結帳列
  const total = cartTotal();
  view.append(
    h("div", { class: "bottom-bar" }, [
      h("div", { class: "total" }, money(total)),
      h("button", { class: "btn btn-primary", style: "flex:0 0 auto", disabled: cart.size === 0, onclick: () => checkout(view) }, "結帳"),
    ])
  );
}

function cartLine(view, item) {
  return h("div", { class: "cart-line" }, [
    h("div", { class: "grow" }, [
      h("div", { class: "title" }, item.product.name),
      h("div", { class: "sub" }, `${money(item.unit_price)} / ${item.product.unit} · 庫存 ${num(item.product.stock)}`),
    ]),
    qtyControl(item, () => paint(view)),
    h("div", { class: "price", style: "min-width:72px;text-align:right" }, money(item.qty * item.unit_price)),
  ]);
}

function qtyControl(item, onChange) {
  const input = h("input", {
    type: "number", inputmode: "decimal", value: num(item.qty),
    onchange: (e) => { item.qty = Math.max(0, Number(e.target.value) || 0); if (item.qty === 0) cart.delete(item.product.id); onChange(); },
  });
  return h("div", { class: "qty-ctrl" }, [
    h("button", { onclick: () => { item.qty = Math.max(0, item.qty - 1); if (item.qty === 0) cart.delete(item.product.id); onChange(); } }, "−"),
    input,
    h("button", { onclick: () => { item.qty += 1; onChange(); } }, "+"),
  ]);
}

function addToCart(product) {
  const existing = cart.get(product.id);
  if (existing) existing.qty += 1;
  else cart.set(product.id, { product, qty: 1, unit_price: Number(product.sale_price) });
}

async function scanAdd(view) {
  const code = await scanBarcode();
  if (!code) return;

  // 阶段1：查本店库存
  const prog = progressToast("① 查詢本店庫存…");
  const p = await getProductByBarcode(code);
  if (p) {
    prog.done(`加入 ${p.name}`, "ok");
    addToCart(p);
    paint(view);
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
    addToCart(saved);
    paint(view);
    toast(`已建檔並加入：${saved.name}`, "ok");
  });
}

async function searchAdd(view) {
  const products = await listProducts();
  sheet("選擇商品", (close) => {
    const box = h("input", { placeholder: "輸入名稱篩選", style: "width:100%;padding:12px;border:1px solid var(--border);border-radius:11px;margin-bottom:10px;background:#fafbfa" });
    const list = h("div", {});
    const draw = (kw = "") => {
      list.innerHTML = "";
      const filtered = products.filter((p) => !kw || (p.name + (p.barcode || "")).toLowerCase().includes(kw.toLowerCase()));
      if (!filtered.length) { list.append(h("div", { class: "empty" }, "無符合")); return; }
      for (const p of filtered) {
        list.append(h("div", { class: "list-item", onclick: () => { addToCart(p); toast(`加入 ${p.name}`, "ok"); close(); paint(view); } }, [
          h("div", { class: "grow" }, [h("div", { class: "title" }, p.name), h("div", { class: "sub" }, `庫存 ${num(p.stock)} ${p.unit}`)]),
          h("div", { class: "price" }, money(p.sale_price)),
        ]));
      }
    };
    box.addEventListener("input", (e) => draw(e.target.value));
    draw();
    return h("div", {}, [box, list]);
  });
}

function cartTotal() {
  let t = 0;
  for (const i of cart.values()) t += i.qty * i.unit_price;
  return t;
}

async function checkout(view) {
  sheet("結帳", (close) => {
    const methods = ["現金", "LINE Pay", "信用卡", "悠遊卡", "行動支付"];
    const sel = h("select", {}, methods.map((m) => h("option", { value: m }, m)));
    sel.value = "現金";
    return h("div", {}, [
      h("p", { class: "section-title" }, `共 ${cart.size} 種商品，合計 ${money(cartTotal())}`),
      field("收款方式", sel),
      h("button", { class: "btn btn-primary btn-block", onclick: async () => {
        const items = [...cart.values()].map((i) => ({ product_id: i.product.id, qty: i.qty, unit_price: i.unit_price }));
        try {
          await createSale({ items, payment_method: sel.value });
          toast("結帳成功，已扣庫存", "ok");
          cart.clear(); close(); paint(view);
        } catch (e) { toast(e.message || "結帳失敗", "err"); }
      } }, `確認收款 ${money(cartTotal())}`),
    ]);
  });
}
