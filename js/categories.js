// 商品分類：预设六类 + 用过的分类自动收集 + 可自訂新增。
// 纯前端维护，分类实际存在每个商品的 category 栏，无需额外资料表。
export const DEFAULT_CATEGORIES = ["乾貨", "調味料", "葉菜類", "根莖類", "水果", "零食"];

let extra = [];

export function allCategories() {
  return Array.from(new Set([...DEFAULT_CATEGORIES, ...extra])).filter(Boolean);
}

// 把商品里用过的分类收进来（去重）
export function mergeCategories(list) {
  for (const c of list || []) {
    const v = (c || "").trim();
    if (v && !DEFAULT_CATEGORIES.includes(v) && !extra.includes(v)) extra.push(v);
  }
}

// 新增一个自订分类
export function addCategory(c) {
  const v = (c || "").trim();
  if (v && !allCategories().includes(v)) extra.push(v);
  return v;
}
