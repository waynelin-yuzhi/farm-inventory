// 轻量 UI 工具：DOM 构建、Toast、底部弹窗、金额格式化

export function h(tag, attrs = {}, children = []) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === "class") el.className = v;
    else if (k === "html") el.innerHTML = v;
    else if (k.startsWith("on") && typeof v === "function") el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === "value") el.value = v;
    else el.setAttribute(k, v);
  }
  for (const c of [].concat(children)) {
    if (c == null || c === false) continue;
    el.append(c.nodeType ? c : document.createTextNode(String(c)));
  }
  return el;
}

export const money = (n) => {
  const x = Number(n || 0);
  return "NT$" + (Number.isInteger(x) ? String(x) : x.toFixed(2));
};
export const num = (n) => {
  const x = Number(n || 0);
  return Number.isInteger(x) ? String(x) : x.toFixed(3).replace(/0+$/, "").replace(/\.$/, "");
};

export function toast(msg, type = "") {
  const t = h("div", { class: "toast " + type }, msg);
  document.getElementById("toast-root").append(t);
  setTimeout(() => t.remove(), 2600);
}

// 画面正中央的「查询中」转圈彈窗，文字可即时更新（查本店库 → 查公开库）
export function busy(msg) {
  const text = h("div", { class: "busy-text" }, msg);
  const overlay = h("div", { class: "busy-overlay" }, h("div", { class: "busy-card" }, [h("div", { class: "spinner" }), text]));
  document.getElementById("modal-root").append(overlay);
  return {
    update(m) { text.textContent = m; },
    remove() { overlay.remove(); },
  };
}

// 三段式步骤进度条彈窗：由左到右一格一格走，最后一点是结果，并给「下一步」按钮
// labels 例：["本店庫存","公開資料庫","結果"]
export function stepper(labels, opts = {}) {
  const n = labels.length;
  const track = h("div", { class: "bar-track" });
  const fill = h("div", { class: "bar-fill" });
  const left = 50 / n;
  track.style.left = left + "%"; track.style.width = ((n - 1) / n * 100) + "%";
  fill.style.left = left + "%";

  const steps = labels.map((lab, i) => h("div", { class: "step" }, [h("div", { class: "dot" }, String(i + 1)), h("div", { class: "lbl" }, lab)]));
  const bar = h("div", { class: "stepper" }, [track, fill, ...steps]);
  const hint = h("div", { class: "stepper-hint" }, "");
  const actions = h("div", { class: "stepper-actions" });
  const overlay = h("div", { class: "busy-overlay" }, h("div", { class: "busy-card stepper-card" },
    [opts.title && h("div", { class: "busy-text" }, opts.title), bar, hint, actions]));
  document.getElementById("modal-root").append(overlay);

  const setDot = (i, cls, txt) => { steps[i].className = "step " + cls; steps[i].querySelector(".dot").textContent = txt; };
  const setFill = (i) => { fill.style.width = (i / n * 100) + "%"; };

  return {
    go(i) {
      for (let k = 0; k < i; k++) setDot(k, "done", "✓");
      setDot(i, "active", String(i + 1));
      for (let k = i + 1; k < n; k++) setDot(k, "", String(k + 1));
      setFill(i);
    },
    result(i, ok, hintText) {
      for (let k = 0; k < i; k++) setDot(k, "done", "✓");
      setDot(i, ok ? "done" : "error", ok ? "✓" : "✕");
      setFill(i);
      hint.textContent = hintText || "";
      hint.style.color = ok ? "var(--green-d)" : "var(--danger)";
    },
    action(label, cb) { actions.append(h("button", { class: "btn btn-primary btn-block", onclick: cb }, label)); },
    close() { overlay.remove(); },
  };
}

// 底部弹窗。renderBody(closeFn) 返回 DOM 节点。
export function sheet(title, renderBody) {
  const root = document.getElementById("modal-root");
  const close = () => overlay.remove();
  const body = renderBody(close);
  const sheetEl = h("div", { class: "modal-sheet" }, [h("h3", {}, title), body]);
  const overlay = h("div", { class: "modal-overlay", onclick: (e) => { if (e.target === overlay) close(); } }, sheetEl);
  root.append(overlay);
  return close;
}

export async function confirmDialog(title, message) {
  return new Promise((resolve) => {
    const close = sheet(title, (done) =>
      h("div", {}, [
        h("p", { class: "section-title" }, message),
        h("div", { class: "row" }, [
          h("button", { class: "btn btn-block", onclick: () => { done(); resolve(false); } }, "取消"),
          h("button", { class: "btn btn-danger btn-block", onclick: () => { done(); resolve(true); } }, "確定"),
        ]),
      ])
    );
  });
}

export function field(label, inputEl) {
  return h("div", { class: "field" }, [h("label", {}, label), inputEl]);
}

export function loading() {
  return h("div", { class: "empty" }, "載入中…");
}
