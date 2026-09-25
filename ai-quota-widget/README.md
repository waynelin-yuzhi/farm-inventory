# AI 額度（Android App ＋ 桌面小工具）

一個只做一件事的 App：把各種 AI 工具的**使用量 / 剩餘額度**集中顯示，並提供桌面小工具隨時查看。

依重要順序排列（App 與小工具都照這個順序）：

| 來源 | 顯示內容 | 需要的憑證 |
|---|---|---|
| Claude API（Console） | 本月花費 ÷ 月預算 %、預估月底花費、超支警示；App 內另有今日花費與各模型花費占比 | Admin API key（`sk-ant-admin…`） |
| Claude 訂閱（Pro / Max） | 5 小時時段 %、本週所有模型 %、本週各模型（Fable / Opus / Sonnet…）%、重置時間、額外用量 | claude.ai 的 `sessionKey` cookie |
| Voyage AI | 本月花費 ÷ 月預算 %、預估月底花費（經 MongoDB Atlas 帳單） | Atlas 服務帳號 Client ID / Secret ＋ 組織 ID |
| Supabase | 各專案資料庫大小、檔案儲存量（對比方案額度）、是否被暫停；組織本月流量 / MAU / Edge Function 次數（讀得到才顯示） | Access token（`sbp_…`） |
| LINE 官方帳號 | 本月已發送訊息 ÷ 方案上限、剩餘則數 | Messaging API Channel access token |
| Google Drive | 儲存空間已用 ÷ 上限 | 自行部署的 Apps Script 網址（程式碼見下方） |
| 自訂 JSON API | 任一工具的用量 / 餘額（可算百分比） | 該工具的 API key |

- 顏色：綠 < 70%、黃 70–89%、紅 ≥ 90%
- 預算提醒：Claude API、Voyage AI 本月花費達預算 80%、100% 時各推播一次（每月重置）
- 自動更新：背景每 30 分鐘（可調，最少 15 分鐘）；開 App、下拉、按小工具 ↻ 會立即更新
- 金鑰只存在手機本機，以 Android Keystore 加密

## 安裝

1. 手機打開本 repo 的 **Releases → 「AI 額度 App（最新版）」**，下載 `ai-quota-widget.apk`
2. 允許瀏覽器「安裝未知應用程式」後安裝
3. 開 App → 右上角 ⚙ 設定 → 貼上金鑰 → 儲存
4. 桌面長按 → 小工具 → 找「AI 額度」拖到桌面

每次 `ai-quota-widget/` 有改動推上 GitHub，Actions 會自動重新打包並更新同一個 Release；下載新版直接覆蓋安裝即可（簽章固定，不用先移除）。

## 取得憑證

**Claude 訂閱 sessionKey**
1. 電腦瀏覽器登入 claude.ai
2. F12 → Application（應用程式）→ Cookies → `https://claude.ai`
3. 複製 `sessionKey` 的值（`sk-ant-sid…`）貼到 App

> 這是 claude.ai 網頁「設定 → 用量」背後的非公開接口，Anthropic 若調整格式可能失效；登出或過期時需重新貼上。

**Claude API Admin key**
console.anthropic.com → Settings → Admin keys → 建立（需組織 admin 權限）。
Anthropic 沒有「剩餘儲值額度」的 API，所以用 App 內自填的「每月預算」算百分比。

**Supabase Access token**
supabase.com/dashboard/account/tokens → Generate new token。有「權限範圍」選項時，只勾讀取：Projects、Organization Settings、Database（Read）。
組織本月用量（流量、MAU）讀的是 Supabase 後台自用接口，讀不到時該卡片不會出現。

**Voyage AI（花費）**
Voyage 本身沒有用量 API，但帳單已併入 MongoDB Atlas，App 透過 Atlas Admin API 讀本月花費：
1. cloud.mongodb.com → Organization → Access Manager → Service Accounts → 建立，權限選 **Organization Billing Viewer**
2. 複製 Client ID、Client Secret（只顯示一次）；組織 ID 在 Organization Settings
3. 優先用 Cost Explorer 只算「AI Model APIs / Automated Embedding / Native Reranking」；讀不到時退回本月未結帳單總額（會標示「Atlas 全部服務」）

限制：剩餘免費 token 沒有 API 可查；若是在舊版 dash.voyageai.com 儲值計費（非 Atlas），目前沒有任何 API 可讀花費。

**LINE Channel access token**
developers.line.biz → 植間的 Provider → Messaging API channel → Messaging API 分頁 → Channel access token（long-lived）。就是植間系統發 LINE 用的同一組。

**Google Drive 用量網址**
script.google.com → 新專案 → 貼上以下程式碼 → 部署 → 新增部署作業 → 網頁應用程式（執行身分：我／存取權：所有人）→ 授權 → 複製網址：

```javascript
function doGet() {
  var used = DriveApp.getStorageUsed();
  var limit = DriveApp.getStorageLimit();
  return ContentService.createTextOutput(JSON.stringify({ used: used, limit: limit }))
    .setMimeType(ContentService.MimeType.JSON);
}
```

LINE 訊息與 Drive 空間用到 80%、100% 也會推播提醒。

**自訂來源範例**

| 欄位 | CloudConvert 範例 |
|---|---|
| 網址 | `https://api.cloudconvert.com/v2/users/me` |
| Headers | `Authorization: Bearer <API key>` |
| 數值欄位路徑 | `data.credits` |
| 這個數值是剩餘量 | ✔ |

路徑語法：`a.b.c`、`items[0].used`。有「上限」欄位就能顯示百分比；API 直接回傳百分比時填「百分比欄位路徑」。

## 開發

- Kotlin、minSdk 26（Android 8.0）、targetSdk 35
- `./gradlew assembleRelease` 產出 `app/build/outputs/apk/release/app-release.apk`
- `signing/shared-debug.keystore` 是個人側載用的固定簽章（密碼 `android`），僅為了讓每次 CI 版本能覆蓋安裝，不適合上架 Google Play

```
app/src/main/java/com/yuzhiplant/aiquota/
  providers/   ClaudeSubscriptionProvider、ClaudeApiProvider、CustomJsonProvider、QuotaRepository
  widget/      QuotaWidgetProvider（桌面小工具）
  work/        RefreshWorker（WorkManager 背景更新）
  ui/          MainActivity、SettingsActivity
  data/        Settings（加密儲存）、ResultCache、Http、Format
```
