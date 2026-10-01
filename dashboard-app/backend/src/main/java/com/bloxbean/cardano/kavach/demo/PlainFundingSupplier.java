package com.bloxbean.cardano.kavach.demo;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Utxo;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Filters automatic funding/collateral discovery while retaining explicit script-input resolution. */
final class PlainFundingSupplier implements UtxoSupplier {
    private final UtxoSupplier delegate;
    PlainFundingSupplier(UtxoSupplier delegate) { this.delegate = delegate; }

    static boolean plain(Utxo input) {
        return input.getReferenceScriptHash() == null && input.getInlineDatum() == null && input.getDataHash() == null
                && input.getAmount() != null && input.getAmount().size() == 1
                && "lovelace".equals(input.getAmount().getFirst().getUnit());
    }

    @Override public List<Utxo> getPage(String address, Integer count, Integer page, OrderEnum order) {
        // Filter after complete bounded pagination: an all-reference page must not hide later funding.
        int requestedCount = count == null ? UtxoSupplier.DEFAULT_NR_OF_ITEMS_TO_FETCH : count;
        int requestedPage = page == null ? 0 : page;
        if (requestedCount <= 0 || requestedPage < 0) throw new IllegalArgumentException("Invalid funding pagination");
        var found = new ArrayList<Utxo>();
        // UtxoSupplier is zero-based; DefaultUtxoSupplier converts to the backend's one-based API.
        for (int sourcePage = 0; sourcePage < 100; sourcePage++) {
            var batch = delegate.getPage(address, 100, sourcePage, order);
            found.addAll(batch.stream().filter(PlainFundingSupplier::plain).toList());
            if (batch.size() < 100) {
                int start = Math.min(found.size(), Math.multiplyExact(requestedPage, requestedCount));
                int end = Math.min(found.size(), Math.addExact(start, requestedCount));
                return List.copyOf(found.subList(start, end));
            }
        }
        throw new IllegalArgumentException("Funding discovery reached its 100-page limit; fewer than 10,000 outputs are supported");
    }

    @Override public Optional<Utxo> getTxOutput(String hash, int index) { return delegate.getTxOutput(hash, index); }
}
