import { h, money, num, toast, sheet, field, loading } from "../ui.js";
import { getProductByBarcode, listProducts, createSale } from "../db.js";
import { scanBarcode } from "../scanner.js";
import { editProduct } from "./products.js";

// 购物车：product_id -> { product, qty, unit_price }
let cart = new Map();

export async function renderCheckout(view) {
  view.innerHTML = "";
  paint(view);
}

function paint(view) {
  view.innerHTML = "";

  const lines = h("div", {});
  if (cart.size === 0) {
    lines.append(h("div", { class: "empty" }, "购物车空 — 扫码或搜索把商品加进来"));
  } else {
    for (const item of cart.values()) lines.append(cartLine(view, item));
  }
  view.append(lines);

  // 操作按钮
  view.append(
    h("div", { class: "row", style: "margin-top:6px" }, [
      h("button", { class: "btn", onclick: () => searchAdd(view) }, "🔎 搜索添加"),
      cart.size > 0 && h("button", { class: "btn", onclick: () => { cart.clear(); paint(view); } }, "🗑 清空"),
    ])
  );

  // 扫码 FAB
  view.append(h("button", { class: "fab-scan", onclick: () => scanAdd(view) }, ["📷 扫码"]));

  // 底部结账栏
  const total = cartTotal();
  view.append(
    h("div", { class: "bottom-bar" }, [
      h("div", { class: "total" }, money(total)),
      h("button", { class: "btn btn-primary", disabled: cart.size === 0, onclick: () => checkout(view) }, "结账"),
    ])
  );
}

function cartLine(view, item) {
  return h("div", { class: "cart-line" }, [
    h("div", { class: "grow" }, [
      h("div", { class: "title" }, item.product.name),
      h("div", { class: "sub" }, `${money(item.unit_price)} / ${item.product.unit} · 库存 ${num(item.product.stock)}`),
    ]),
    qtyControl(item, () => paint(view)),
    h("div", { class: "price", style: "min-width:62px;text-align:right" }, money(item.qty * item.unit_price)),
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
  const p = await getProductByBarcode(code);
  if (!p) {
    toast("未找到该条码商品，请先建档", "err");
    editProduct(view, { barcode: code });
    return;
  }
  addToCart(p);
  toast(`加入 ${p.name}`, "ok");
  paint(view);
}

async function searchAdd(view) {
  const products = await listProducts();
  sheet("选择商品", (close) => {
    const box = h("input", { placeholder: "输入名称筛选", style: "width:100%;padding:12px;border:1px solid var(--border);border-radius:11px;margin-bottom:10px;background:#fafbfa" });
    const list = h("div", {});
    const draw = (kw = "") => {
      list.innerHTML = "";
      const filtered = products.filter((p) => !kw || (p.name + (p.barcode || "")).toLowerCase().includes(kw.toLowerCase()));
      if (!filtered.length) { list.append(h("div", { class: "empty" }, "无匹配")); return; }
      for (const p of filtered) {
        list.append(h("div", { class: "list-item", onclick: () => { addToCart(p); toast(`加入 ${p.name}`, "ok"); close(); paint(view); } }, [
          h("div", { class: "grow" }, [h("div", { class: "title" }, p.name), h("div", { class: "sub" }, `库存 ${num(p.stock)} ${p.unit}`)]),
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
  sheet("结账", (close) => {
    let payment = "现金";
    const methods = ["现金", "微信", "支付宝", "银行卡"];
    const sel = h("select", {}, methods.map((m) => h("option", { value: m }, m)));
    sel.value = payment;
    return h("div", {}, [
      h("p", { class: "section-title" }, `共 ${cart.size} 种商品，合计 ${money(cartTotal())}`),
      field("收款方式", sel),
      h("button", { class: "btn btn-primary btn-block", onclick: async () => {
        const items = [...cart.values()].map((i) => ({ product_id: i.product.id, qty: i.qty, unit_price: i.unit_price }));
        try {
          await createSale({ items, payment_method: sel.value });
          toast("结账成功，已扣库存", "ok");
          cart.clear(); close(); paint(view);
        } catch (e) { toast(e.message || "结账失败", "err"); }
      } }, `确认收款 ${money(cartTotal())}`),
    ]);
  });
}
