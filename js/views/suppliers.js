import { h, toast, sheet, field, confirmDialog, loading } from "../ui.js";
import { listSuppliers, upsertSupplier, deleteSupplier } from "../db.js";

export async function renderSuppliers(view) {
  view.append(loading());
  await refresh(view);
}

async function refresh(view) {
  const suppliers = await listSuppliers();
  view.innerHTML = "";
  view.append(
    h("button", { class: "btn btn-primary btn-block", style: "margin-bottom:14px", onclick: () => edit(view, null) }, "+ 新增廠商")
  );
  if (!suppliers.length) {
    view.append(h("div", { class: "empty" }, "還沒有廠商資料"));
    return;
  }
  for (const s of suppliers) {
    view.append(
      h("div", { class: "list-item", onclick: () => edit(view, s) }, [
        h("div", { class: "grow" }, [
          h("div", { class: "title" }, s.name),
          h("div", { class: "sub" }, [s.contact, s.phone].filter(Boolean).join(" · ") || "無聯絡方式"),
        ]),
      ])
    );
  }
}

function edit(view, s) {
  s = s || {};
  const isNew = !s.id;
  const g = {};
  const inp = (k, attrs) => (g[k] = h("input", { value: s[k] ?? "", ...attrs }));
  sheet(isNew ? "新增廠商" : "編輯廠商", (close) =>
    h("div", {}, [
      field("廠商名稱 *", inp("name", { placeholder: "如：城東蔬果批發" })),
      field("聯絡人", inp("contact", {})),
      field("電話", inp("phone", { type: "tel", inputmode: "tel" })),
      field("備註", inp("note", {})),
      h("button", { class: "btn btn-primary btn-block", onclick: async () => {
        if (!g.name.value.trim()) return toast("請填寫名稱", "err");
        try {
          await upsertSupplier({ id: s.id, name: g.name.value.trim(), contact: g.contact.value.trim(), phone: g.phone.value.trim(), note: g.note.value.trim() });
          toast("已儲存", "ok"); close(); refresh(view);
        } catch (e) { toast(e.message || "儲存失敗", "err"); }
      } }, "儲存"),
      !isNew && h("button", { class: "btn btn-danger btn-block", onclick: async () => {
        if (await confirmDialog("刪除廠商", `確定刪除「${s.name}」？`)) {
          try { await deleteSupplier(s.id); toast("已刪除", "ok"); close(); refresh(view); }
          catch (e) { toast("刪除失敗", "err"); }
        }
      } }, "刪除"),
    ])
  );
}
