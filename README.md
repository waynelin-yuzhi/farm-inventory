# 🥬 农产品小店 · 进销存 MVP

一个**手机优先**的农产品小店进销存网页 App（PWA）。手机摄像头扫条码即可：

- 📥 **扫码进货**：扫条码 → 选厂商、填日期 → 录入数量与成本 → 一键入库（自动加库存、更新成本、留库存流水）
- 🛒 **扫码结账**：扫条码 → 自动按售价加入购物车 → 调整数量 → 收款（自动扣库存，库存不足会拦截）
- 📦 **商品资料库**：扫到新条码自动建档；名称 / 售价 / 单位（支持按斤/公斤等小数计量）/ 分类 / 库存预警线
- 🏭 **厂商资料库**：维护供应商联系方式
- 📊 **总览**：今日营业额、单数、商品种类、库存成本估值、低库存预警、最近销售

> 数据存于 **Supabase（云端）**，多台手机 / 多人登录后共用同一份库存与单据。

---

## 一、技术架构

- **前端**：纯静态文件（HTML/CSS/原生 ES Module JS），零构建，可托管在任意静态空间。
- **扫码**：优先用浏览器原生 `BarcodeDetector`（安卓 Chrome），不支持时回退 [ZXing](https://github.com/zxing-js)（覆盖 iOS Safari）。
- **后端**：Supabase（托管 Postgres + Auth + 自动 API）。进货/结账用数据库函数（RPC）保证「写单据 + 改库存」原子化。

```
index.html            应用外壳 + 登录页 + 扫码层
css/styles.css         手机优先样式（底部 Tab、底部弹窗、扫码框）
js/
  config.js            ★ 在这里填 Supabase URL / anon key
  supabase.js          初始化客户端
  db.js                所有数据读写
  scanner.js           扫码（原生 + ZXing 回退）
  ui.js                DOM/Toast/弹窗工具
  router.js            哈希路由
  app.js               登录态 + 启动 + Service Worker
  views/               总览 / 结账 / 进货 / 商品 / 厂商 五个页面
supabase/schema.sql    ★ 数据库建表 + RPC + 行级安全，整段执行一次
manifest.webmanifest   PWA 清单
sw.js                  Service Worker（缓存外壳）
icons/                 应用图标
```

---

## 二、部署步骤（约 10 分钟）

### 1. 建 Supabase 项目
1. 到 [supabase.com](https://supabase.com) 注册并 **New project**（免费档够用）。
2. 左侧 **SQL Editor** → 新建查询 → 把 `supabase/schema.sql` **整段粘贴执行**。
3. **Project Settings → API** 里复制：
   - `Project URL`
   - `anon` `public` key
4. **（MVP 建议）** Authentication → Providers → Email：先**关闭 "Confirm email"**，这样注册后可直接登录、不必收邮件。

### 2. 填配置
打开 `js/config.js`，把上面两个值填进去：
```js
export const SUPABASE_URL = "https://你的项目.supabase.co";
export const SUPABASE_ANON_KEY = "你的 anon key";
```

### 3. 用 HTTPS 托管（摄像头要求 HTTPS）
任选其一，把整个目录传上去即可（纯静态）：
- **Netlify / Vercel / Cloudflare Pages**：拖拽或连 GitHub 仓库，零配置。
- **GitHub Pages**：仓库 Settings → Pages → 选分支即可。

> 本地自测：`localhost` 也被浏览器视为安全来源，可摄像头扫码。
> 例如在项目根目录跑 `python3 -m http.server 8000`，手机与电脑同网时用电脑局域网 IP 访问需 HTTPS，故扫码请用线上 HTTPS 或本机 localhost。

### 4. 手机使用
用手机浏览器打开你的 HTTPS 网址 → 注册/登录 → 浏览器菜单「添加到主屏幕」，即可像 App 一样全屏使用。

---

## 三、典型流程

1. **先建商品**：进「商品」→ 右下角「📷 扫码建档」→ 扫商品条码 → 填名称、售价、单位 → 保存。
   （农产品没条码也行：直接「+ 新增商品」，条码留空。）
2. **进货入库**：进「进货」→ 选厂商和日期 → 扫码/搜索加入 → 填数量与成本 → 「入库」。
3. **收银结账**：进「结账」→ 扫码把商品加进购物车 → 调数量 → 「结账」选收款方式 → 完成，库存自动扣减。
4. **看经营**：「总览」查看今日营业额、库存预警等。

---

## 四、已知边界（MVP，后续可加）

- 单店单租户：所有登录用户共享同一份数据（适合一家小店）。多店隔离需加 `shop_id` 与对应 RLS。
- 暂未做销售单/进货单的详情查看与退货、库存盘点调整界面（数据已留 `stock_movements` 流水，便于扩展）。
- 称重商品目前手动输入重量；如需对接电子秤条码（含重量/金额）可再加解析。
- 未接入公开商品库联网带名，新条码需手动建档一次（农产品命中率低，更可控）。
