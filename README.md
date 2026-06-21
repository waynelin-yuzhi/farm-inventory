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

---

## 五、下一阶段规划：LIFF + LINE 登入（已确认，暂未实作）

未来把这套 App 搬进 **LINE 官方帐号**，以 LINE 身分登入并依 ID 赋予权限。主体程式不动，只需新增「登入模组 + 后端身分交换 + 权限表」三块。

**权限：老闆 / 员工 两层**

| 角色 | 结帐 | 进货 | 改商品·售价 | 看报表·授权管理 |
|---|---|---|---|---|
| 老闆 owner | ✅ | ✅ | ✅ | ✅ |
| 员工 staff | ✅ | ✅ | — | — |

**预计改动**

1. 登入改用 LIFF：`liff.init()` → `liff.getIDToken()` 取得 LINE ID Token，取代现在的 email/密码登入页。
2. 新增 Supabase **Edge Function**：验证 LINE ID Token → 查 `app_users(line_user_id, role, active)` → 签发带 `app_role` claim 的 Supabase session。
3. 收紧 RLS：由「登入即全开」改为依 `app_role` 分权（员工不可改售价/不可看报表与授权）。
4. 扫码沿用现成 `getUserMedia`+ZXing（LIFF 内建浏览器可用）；`liff.scanCodeV2()` 作为有就用的加速路径（iOS 支援不稳，故仅当后备）。
5. LINE Developers：建 LINE Login channel + LIFF app（endpoint 指向托管网址、size=Full），官方帐号图文选单放按钮开启。

> 先决条件：先让本版在 Supabase 跑通，再进入此阶段；届时需提供 LIFF ID 与 LINE Login channel 资讯。

---

## 六、下一阶段规划：拍照 / 条码自动带入商品资料（已确认，暂未实作）

「新增商品」时尽量减少手填：扫码或拍照 → 自动带出名称、分类、单位、品牌、规格；售价与成本仍由人手填（每店不同、照片读不到）。

**两条互补路径**
1. **条码查免费公开库**（如 Open Food Facts）：标准包装商品命中即带名，零成本、零金钥、免后端。
2. **拍照 AI 辨识**：农产品、地方小厂商品走此路。手机拍照 → 压缩 → Supabase **Edge Function**（藏 Anthropic 金钥）→ 视觉模型回传结构化 JSON → 前端回填表单。

**模型与成本**
- 选定 **Claude Haiku 4.5**（`claude-haiku-4-5`，省成本；输入 $1 / 输出 $5 每百万 token）。
- 辨识只在「建档当下」呼叫一次（非每次扫码结帐），单次约 1~1.5K token 输入 + 约 100 token 输出，成本极低。
- 用 Claude 结构化输出（`output_config.format`）锁定回传 JSON 格式，保证可直接回填。
- 金钥务必放 Edge Function 环境变数，绝不可进前端或 repo。

**分阶段**
- 阶段一：条码 → 免费公开库带名（最快、免金钥）
- 阶段二：拍照 → Haiku 4.5 视觉辨识（需 Edge Function + Anthropic 金钥）
- 阶段三：连规格/重量一起读；支援电子秤条码

> 先决条件：先让本版在 Supabase 跑通；阶段二需部署 Edge Function 并提供一把 Anthropic API 金钥（存为环境变数）。
