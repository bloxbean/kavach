import { useEffect, useRef, useState } from "react";
import { cameraLease } from "./approvalScan";

export function ApprovalScanner({ onRead, onClose }: { onRead: (text: string) => void; onClose: () => void }) {
  const video = useRef<HTMLVideoElement>(null);
  const callbacks = useRef({ onRead, onClose });
  callbacks.current = { onRead, onClose };
  const [status, setStatus] = useState("Waiting for camera permission…");
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const lease = cameraLease();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let deadline: ReturnType<typeof setTimeout> | undefined;
    const stop = () => { lease.close(); clearTimeout(timer); clearTimeout(deadline); if (video.current) video.current.srcObject = null; };
    const fail = (message: string) => { if (lease.closed) return; stop(); setFailed(true); setStatus(message); };
    const hidden = () => { if (document.hidden) { stop(); callbacks.current.onClose(); } };
    document.addEventListener("visibilitychange", hidden);
    async function start() {
      try {
        if (!navigator.mediaDevices?.getUserMedia) throw new Error("Camera access is unavailable. Use localhost or HTTPS, or paste the approval JSON.");
        const stream = await navigator.mediaDevices.getUserMedia({ audio: false, video: { width: { ideal: 1280 }, height: { ideal: 720 } } });
        if (!lease.accept(stream)) return;
        const decode = (await import("jsqr")).default;
        if (lease.closed || !video.current) return;
        video.current.srcObject = stream;
        await video.current.play();
        if (lease.closed) return;
        setStatus("Show the ‘Approval ready’ QR on your iPhone to this camera. Keep the whole code visible and steady.");
        const canvas = document.createElement("canvas");
        const context = canvas.getContext("2d", { willReadFrequently: true });
        if (!context) throw new Error("This browser cannot scan camera frames. Paste the approval JSON instead.");
        deadline = setTimeout(() => fail("Scanning paused after 90 seconds. Close and try again, or paste the approval JSON."), 90_000);
        const scan = () => {
          if (lease.closed || !video.current) return;
          const source = video.current;
          try {
            if (source.readyState >= 2 && source.videoWidth && source.videoHeight) {
              const scale = Math.min(1, 1280 / source.videoWidth);
              canvas.width = Math.round(source.videoWidth * scale); canvas.height = Math.round(source.videoHeight * scale);
              context.drawImage(source, 0, 0, canvas.width, canvas.height);
              const frame = context.getImageData(0, 0, canvas.width, canvas.height);
              const result = decode(frame.data, frame.width, frame.height, { inversionAttempts: "attemptBoth" });
              if (result?.data) { stop(); callbacks.current.onRead(result.data); return; }
            }
            timer = setTimeout(scan, 180);
          } catch { fail("Could not read the camera. Close and try again, or paste the approval JSON."); }
        };
        scan();
      } catch (error) {
        const name = error instanceof Error ? error.name : "";
        fail(name === "NotAllowedError" ? "Camera permission was denied. Allow camera access for this site, or use paste below."
          : name === "NotFoundError" ? "No camera found. Use paste below."
          : name === "NotReadableError" ? "The camera is busy or unavailable. Close other camera apps and try again, or use paste."
          : error instanceof Error ? error.message : "Could not start the camera. Use paste below.");
      }
    }
    void start();
    return () => { stop(); document.removeEventListener("visibilitychange", hidden); };
  }, []);
  return <section className="approval-scanner" aria-label="Scan phone approval">
    {!failed && <video ref={video} muted playsInline autoPlay aria-label="Camera preview for phone approval QR" />}
    <p role="status">{status}</p>
    <p>Camera frames stay in this browser. Only the decoded approval is sent to your local Kavach backend for verification. Scanning adds the approval; it does not submit a transaction.</p>
    <button type="button" className="button" onClick={onClose}>{failed ? "Close scanner" : "Stop camera"}</button>
  </section>;
}
