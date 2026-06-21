// 用免費的公開商品資料庫 Open Food Facts 依條碼查商品名稱。
// 純前端、免金鑰、零成本；手機瀏覽器直接連它的公開 API（支援跨網域）。
// 查不到（多數農產品、地方小廠）會回 null，改由人手填。
export async function lookupBarcode(barcode) {
  const code = (barcode || "").trim();
  if (!code) return null;
  try {
    const fields = [
      "product_name", "product_name_zh", "product_name_zh_tw",
      "generic_name", "brands", "quantity", "categories",
    ].join(",");
    const url = `https://world.openfoodfacts.org/api/v2/product/${encodeURIComponent(code)}.json?fields=${fields}`;
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 6000);
    const res = await fetch(url, { signal: ctrl.signal, headers: { Accept: "application/json" } });
    clearTimeout(timer);
    if (!res.ok) return null;
    const data = await res.json();
    if (!data || data.status !== 1 || !data.product) return null;
    const p = data.product;

    const name = (p.product_name_zh_tw || p.product_name_zh || p.product_name || p.generic_name || "").trim();
    const brand = (p.brands || "").split(",")[0].trim();
    const category = (p.categories || "").split(",").pop().trim();
    const quantity = (p.quantity || "").trim();
    const finalName = name || brand;
    if (!finalName) return null;

    return { name: finalName, brand, category, quantity };
  } catch {
    return null; // 逾時 / 離線 / 解析失敗，一律回 null 改手填
  }
}
