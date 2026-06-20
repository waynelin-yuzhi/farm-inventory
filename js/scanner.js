// 条码扫描：优先用浏览器原生 BarcodeDetector（安卓 Chrome 快），
// 不支持时回退到 ZXing（覆盖 iOS Safari 等）。
// 用法：const code = await scanBarcode();  // 取消返回 null
import { BrowserMultiFormatReader } from "https://esm.sh/@zxing/browser@0.1.5";

const modal = () => document.getElementById("scanner-modal");
const video = () => document.getElementById("scanner-video");

export function scanBarcode() {
  return new Promise((resolve) => {
    const m = modal();
    const v = video();
    let stopped = false;
    let stream = null;
    let zxing = null;
    let rafId = null;

    const cleanup = () => {
      if (stopped) return;
      stopped = true;
      if (rafId) cancelAnimationFrame(rafId);
      try { zxing && zxing.reset && zxing.reset(); } catch {}
      try { v.srcObject = null; } catch {}
      if (stream) stream.getTracks().forEach((t) => t.stop());
      m.classList.add("hidden");
      closeBtn.removeEventListener("click", onCancel);
      manualBtn.removeEventListener("click", onManual);
    };
    const finish = (code) => { cleanup(); resolve(code); };
    const onCancel = () => finish(null);
    const onManual = () => {
      const code = window.prompt("请输入条码：");
      finish(code && code.trim() ? code.trim() : null);
    };

    const closeBtn = document.getElementById("scanner-close");
    const manualBtn = document.getElementById("scanner-manual");
    closeBtn.addEventListener("click", onCancel);
    manualBtn.addEventListener("click", onManual);

    m.classList.remove("hidden");

    const start = async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: { ideal: "environment" } },
          audio: false,
        });
        v.srcObject = stream;
        await v.play();

        if ("BarcodeDetector" in window) {
          const detector = new window.BarcodeDetector({
            formats: ["ean_13", "ean_8", "upc_a", "upc_e", "code_128", "code_39", "qr_code", "itf"],
          });
          const tick = async () => {
            if (stopped) return;
            try {
              const codes = await detector.detect(v);
              if (codes && codes.length) return finish(codes[0].rawValue);
            } catch {}
            rafId = requestAnimationFrame(tick);
          };
          rafId = requestAnimationFrame(tick);
        } else {
          zxing = new BrowserMultiFormatReader();
          zxing.decodeFromStream(stream, v, (result) => {
            if (result && !stopped) finish(result.getText());
          });
        }
      } catch (err) {
        cleanup();
        const code = window.prompt("无法打开摄像头（需 HTTPS 并授权）。可手动输入条码：");
        resolve(code && code.trim() ? code.trim() : null);
      }
    };
    start();
  });
}
