export type ApprovalTarget = { id: number; publicKey: string; payload: string };

/** Cheap framing checks only. The backend remains the signature and request-ID authority. */
export function parsePhoneApproval(text: string, target: ApprovalTarget) {
  if (new TextEncoder().encode(text).length > 8192) throw new Error("Approval exceeds 8 KB.");
  let response: Record<string, unknown>;
  try { response = JSON.parse(text); } catch { throw new Error("This QR is not approval JSON. Show the phone’s ‘Approval ready’ QR."); }
  if (!response || Array.isArray(response) || response.version !== 1 || response.kind !== "approval")
    throw new Error("Scan the phone’s approval QR, not a pairing or request QR.");
  if (response.credentialID !== target.id || response.publicKey !== target.publicKey || response.digest !== target.payload)
    throw new Error("This approval belongs to a different key or request. Show the approval for the selected phone credential.");
  if (typeof response.requestID !== "string" || !/^[0-9a-f-]{36}$/i.test(response.requestID)
      || !["kavach-cose-genesis-v1", "kavach-cose-spend-v1"].includes(String(response.profile))
      || typeof response.signature !== "string" || !/^(?:[0-9a-f]{2})+$/i.test(response.signature)
      || typeof response.key !== "string" || !/^(?:[0-9a-f]{2})+$/i.test(response.key))
    throw new Error("Unsupported or incomplete phone approval.");
  return response;
}

/** Release camera ownership even when permission resolves after the scanner was closed. */
export function cameraLease() {
  let closed = false;
  let stream: MediaStream | undefined;
  return {
    accept(value: MediaStream) {
      if (closed) { value.getTracks().forEach(track => track.stop()); return false; }
      stream = value; return true;
    },
    close() { closed = true; stream?.getTracks().forEach(track => track.stop()); stream = undefined; },
    get closed() { return closed; },
  };
}
