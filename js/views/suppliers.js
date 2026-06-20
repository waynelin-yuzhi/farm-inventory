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
    h("button", { class: "btn btn-primary btn-block", style: "margin-bottom:14px", onclick: () => edit(view, null) }, "+ 新增厂商")
  );
  if (!suppliers.length) {
    view.append(h("div", { class: "empty" }, "还没有厂商资料"));
    return;
  }
  for (const s of suppliers) {
    view.append(
      h("div", { class: "list-item", onclick: () => edit(view, s) }, [
        h("div", { class: "grow" }, [
          h("div", { class: "title" }, s.name),
          h("div", { class: "sub" }, [s.contact, s.phone].filter(Boolean).join(" · ") || "无联系方式"),
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
  sheet(isNew ? "新增厂商" : "编辑厂商", (close) =>
    h("div", {}, [
      field("厂商名称 *", inp("name", { placeholder: "如：城东蔬菜批发" })),
      field("联系人", inp("contact", {})),
      field("电话", inp("phone", { type: "tel", inputmode: "tel" })),
      field("备注", inp("note", {})),
      h("button", { class: "btn btn-primary btn-block", onclick: async () => {
        if (!g.name.value.trim()) return toast("请填写名称", "err");
        try {
          await upsertSupplier({ id: s.id, name: g.name.value.trim(), contact: g.contact.value.trim(), phone: g.phone.value.trim(), note: g.note.value.trim() });
          toast("已保存", "ok"); close(); refresh(view);
        } catch (e) { toast(e.message || "保存失败", "err"); }
      } }, "保存"),
      !isNew && h("button", { class: "btn btn-danger btn-block", onclick: async () => {
        if (await confirmDialog("删除厂商", `确定删除「${s.name}」？`)) {
          try { await deleteSupplier(s.id); toast("已删除", "ok"); close(); refresh(view); }
          catch (e) { toast("删除失败", "err"); }
        }
      } }, "删除"),
    ])
  );
}
