import type { Plan } from './api';
export function approvalPurpose(purpose: string) {
  return ({ genesis: 'Enroll this key', operation: 'Authorize this change', candidate: 'Confirm this key in the new policy', configuration: 'Confirm this key in the new configuration', possession: 'Confirm this key in the new policy' } as Record<string, string>)[purpose] || `Approve ${purpose}`;
}
export function workflowStage(plan: Plan) {
  if (plan.status === 'Submitted') return plan.confirmed ? 3 : 2;
  return plan.transaction ? 1 : 0;
}
export function setupPhase(step: number) { return step <= 6 ? 0 : step === 7 ? 1 : 2; }

/** Only explicit backend role metadata can classify a signature as funding-only. */
export function isFeePayerOnly(plan: Plan) {
  return !!plan.feePayer && plan.transactionAuthoritySigners?.length === 0;
}
export function transactionRole(plan: Plan, hash: string) {
  const funding = plan.feePayer?.paymentKeyHash === hash;
  const authority = plan.transactionAuthoritySigners?.includes(hash);
  if (funding && authority) return 'Fee payer + transaction-based account authority';
  if (funding) return 'Fee payer';
  if (authority) return 'Transaction-based account authority';
  return 'Required wallet signer';
}
