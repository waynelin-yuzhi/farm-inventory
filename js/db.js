// 数据访问层：封装所有 Supabase 读写
import { supabase } from "./supabase.js";

const unwrap = ({ data, error }) => { if (error) throw error; return data; };

// ---------- 商品 ----------
export async function listProducts(search = "") {
  let q = supabase.from("products").select("*").order("name");
  if (search) q = q.or(`name.ilike.%${search}%,barcode.ilike.%${search}%,category.ilike.%${search}%`);
  return unwrap(await q);
}
export async function getProductByBarcode(barcode) {
  const { data, error } = await supabase.from("products").select("*").eq("barcode", barcode).maybeSingle();
  if (error) throw error;
  return data;
}
export async function upsertProduct(p) {
  const payload = {
    barcode: p.barcode || null,
    name: p.name,
    category: p.category || null,
    unit: p.unit || "件",
    sale_price: Number(p.sale_price) || 0,
    reorder_level: Number(p.reorder_level) || 0,
    note: p.note || null,
  };
  if (p.id) {
    return unwrap(await supabase.from("products").update(payload).eq("id", p.id).select().single());
  }
  return unwrap(await supabase.from("products").insert(payload).select().single());
}
export async function deleteProduct(id) {
  const { error } = await supabase.from("products").delete().eq("id", id);
  if (error) throw error;
}

// 直接调整库存（盘点/期初）：设为新值，并记一笔 adjust 流水
export async function adjustStock(productId, newStock, reason) {
  const cur = await supabase.from("products").select("stock").eq("id", productId).single();
  if (cur.error) throw cur.error;
  const change = Number(newStock) - Number(cur.data.stock);
  if (change === 0) return;
  const up = await supabase.from("products")
    .update({ stock: newStock, updated_at: new Date().toISOString() }).eq("id", productId);
  if (up.error) throw up.error;
  await insertMovement(productId, change, "adjust", reason);
}

// 损耗/报废：扣减库存并记一笔 waste 流水
export async function recordWaste(productId, qty, reason) {
  const q = Number(qty);
  if (!q || q <= 0) throw new Error("數量需大於 0");
  const cur = await supabase.from("products").select("stock").eq("id", productId).single();
  if (cur.error) throw cur.error;
  const up = await supabase.from("products")
    .update({ stock: Number(cur.data.stock) - q, updated_at: new Date().toISOString() }).eq("id", productId);
  if (up.error) throw up.error;
  await insertMovement(productId, -q, "waste", reason);
}

// 写库存流水（仅审计）。note 列若尚未建立会退而不带 note；失败不影响库存。
async function insertMovement(productId, change, type, note) {
  const base = { product_id: productId, change, type };
  const r = await supabase.from("stock_movements").insert(note ? { ...base, note } : base);
  if (r.error && note) await supabase.from("stock_movements").insert(base);
}

// ---------- 厂商 ----------
export async function listSuppliers() {
  return unwrap(await supabase.from("suppliers").select("*").order("name"));
}
export async function upsertSupplier(s) {
  const payload = { name: s.name, contact: s.contact || null, phone: s.phone || null, note: s.note || null };
  if (s.id) return unwrap(await supabase.from("suppliers").update(payload).eq("id", s.id).select().single());
  return unwrap(await supabase.from("suppliers").insert(payload).select().single());
}
export async function deleteSupplier(id) {
  const { error } = await supabase.from("suppliers").delete().eq("id", id);
  if (error) throw error;
}

// ---------- 进货 / 结账 ----------
export async function createPurchase({ supplier_id, date, note, items }) {
  return unwrap(await supabase.rpc("create_purchase", {
    p_supplier_id: supplier_id || null,
    p_date: date || null,
    p_note: note || null,
    p_items: items,
  }));
}
export async function createSale({ items, payment_method, note }) {
  return unwrap(await supabase.rpc("create_sale", {
    p_items: items,
    p_payment_method: payment_method || "现金",
    p_note: note || null,
  }));
}

// ---------- 总览统计 ----------
export async function dashboardStats() {
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const [products, salesToday, lowStock] = await Promise.all([
    supabase.from("products").select("id, stock, last_cost", { count: "exact" }),
    supabase.from("sales").select("total").gte("sale_date", today.toISOString()),
    supabase.from("products").select("id, name, stock, unit, reorder_level").gt("reorder_level", 0),
  ]);
  if (products.error) throw products.error;
  if (salesToday.error) throw salesToday.error;
  if (lowStock.error) throw lowStock.error;

  const productCount = products.count ?? products.data.length;
  const inventoryValue = products.data.reduce((s, p) => s + Number(p.stock) * Number(p.last_cost), 0);
  const todayTotal = salesToday.data.reduce((s, r) => s + Number(r.total), 0);
  const todayOrders = salesToday.data.length;
  const low = lowStock.data.filter((p) => Number(p.stock) <= Number(p.reorder_level));
  return { productCount, inventoryValue, todayTotal, todayOrders, low };
}

export async function recentSales(limit = 15) {
  return unwrap(await supabase.from("sales").select("*").order("sale_date", { ascending: false }).limit(limit));
}

// 查单笔销售的明细（含商品名称/单位）
export async function getSaleDetail(saleId) {
  return unwrap(await supabase
    .from("sale_items")
    .select("qty, unit_price, subtotal, products(name, unit)")
    .eq("sale_id", saleId));
}
