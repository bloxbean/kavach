/** Remove an unassigned creation key without changing the identities authorized by any policy. */
export function policiesAfterSignerRemoval<T extends { members: number[] }>(policies: T[], id: number): T[] {
  if (policies.some(p => p.members.includes(id))) throw new Error("Clear this signer's policy assignments before removing it.");
  return policies.map(p => ({ ...p, members: p.members.map(m => m > id ? m - 1 : m) }));
}
