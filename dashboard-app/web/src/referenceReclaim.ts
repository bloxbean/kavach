import type { ReferenceView } from './api.ts';

/** UI preflight only: the backend re-resolves this exact output and verifies publisher authority. */
export function referenceReclaim(reference: ReferenceView | null, sponsor: string, acknowledged: boolean) {
  if (!acknowledged) throw new Error('Acknowledge removal of this reference before preparing reclamation.');
  if (!reference || !reference.available || reference.hosting !== 'vault' || reference.reclaimable !== true)
    throw new Error('Select an available publisher-vault output. Historical references cannot use this action.');
  if (!/^[0-9a-f]{64}$/i.test(reference.transactionHash || '')
    || !Number.isSafeInteger(reference.outputIndex) || reference.outputIndex! < 0
    || !/^[0-9a-f]{56}$/i.test(reference.scriptHash)
    || !/^[0-9a-f]{56}$/i.test(reference.publisherKeyHash || ''))
    throw new Error('Incomplete reference identity. Refresh the account before reclaiming.');
  // CIP-30 returns address bytes as hex. Only supported testnet key-payment shapes qualify.
  if (!/^(?:[02]0[0-9a-f]{112}|60[0-9a-f]{56})$/i.test(sponsor)
    || sponsor.slice(2, 58).toLowerCase() !== reference.publisherKeyHash!.toLowerCase())
    throw new Error('Connect the publisher wallet shown for this vault, then prepare a fresh request.');
  return { transactionHash: reference.transactionHash!, outputIndex: reference.outputIndex!,
    scriptHash: reference.scriptHash, acknowledge: true };
}
