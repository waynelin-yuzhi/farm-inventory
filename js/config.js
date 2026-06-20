// ============================================================
// 在这里填入你的 Supabase 项目信息（控制台 -> Project Settings -> API）
//   SUPABASE_URL：形如 https://abcdwxyz.supabase.co
//   SUPABASE_ANON_KEY：anon / public 那把 key（可公开，配合 RLS 使用）
// 填好后保存即可，无需打包构建。
// ============================================================
export const SUPABASE_URL = "https://YOUR-PROJECT.supabase.co";
export const SUPABASE_ANON_KEY = "YOUR-ANON-KEY";

export const isConfigured = () =>
  SUPABASE_URL.startsWith("https://") &&
  !SUPABASE_URL.includes("YOUR-PROJECT") &&
  SUPABASE_ANON_KEY.length > 20 &&
  !SUPABASE_ANON_KEY.includes("YOUR-ANON-KEY");
