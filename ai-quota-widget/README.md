# AI 額度（Android App ＋ 桌面小工具）

一個只做一件事的 App：把各種 AI 工具的**使用量 / 剩餘額度**集中顯示，並提供桌面小工具隨時查看。

| 來源 | 顯示內容 | 需要的憑證 |
|---|---|---|
| Claude 訂閱（Pro / Max） | 5 小時時段 %、本週所有模型 %、本週各模型（Fable / Opus / Sonnet…）%、重置時間、額外用量 | claude.ai 的 `sessionKey` cookie |
| Claude API（Console） | 本月花費（對比自訂月預算的 %）、今日花費 | Admin API key（`sk-ant-admin…`） |
| 自訂 JSON API | 任一工具的用量 / 餘額（可算百分比） | 該工具的 API key |

- 顏色：綠 < 70%、黃 70–89%、紅 ≥ 90%
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
