package com.bloxbean.cardano.kavach.sdk;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetLib.Usage;
import com.bloxbean.cardano.kavach.protocol.PeriodicBudgetCodec;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;

/** Authenticated counter preparation and transaction attachment for budget-capable accounts. */
public final class PeriodicBudgetTransfer {
    private PeriodicBudgetTransfer() {}
    /** Validated counter input and exact computed successor; approval remains with the account module. */
    public record Prepared(Utxo input, PlutusV3Script validator, Usage next) {}

    /** Validates full NFT custody and computes usage from the builder's already-accounted ADA debit. */
    public static Prepared prepare(AccountState state, Utxo input, BigInteger debit, BigInteger lower, BigInteger upper) throws Exception {
        if (!PeriodicBudgetDeployment.supports(state)) throw new IllegalArgumentException("Account core does not support periodic budgets");
        var configuration = PeriodicBudgetCodec.decode(state.authConfig());
        var budget = configuration.budget().orElseThrow(() -> new IllegalArgumentException("Budget is not enabled"));
        if (budget.limit().signum() == 0) throw new IllegalArgumentException("Budget is disabled");
        var validator = PeriodicBudgetDeployment.validator(state);
        var network = new Network(state.deploymentDomain().networkId().intValueExact(), state.deploymentDomain().networkMagic().longValueExact());
        var expectedAddress = AddressProvider.getEntAddress(validator, network).toBech32();
        var amounts = AccountTransfer.quantities(input.getAmount());
        var unit = HexFormat.of().formatHex(budget.counter().policy()) + HexFormat.of().formatHex(budget.counter().name());
        if (!expectedAddress.equals(input.getAddress()) || input.getInlineDatum() == null || input.getReferenceScriptHash() != null
                || amounts.size() != 2 || !BigInteger.ONE.equals(amounts.get(unit)))
            throw new IllegalArgumentException("Wrong counter NFT, quantity, address or datum custody");
        var previous = PeriodicBudgetCodec.usage(PlutusDataAdapter.fromClientLib(
                PlutusData.deserialize(HexFormat.of().parseHex(input.getInlineDatum()))));
        return new Prepared(input, validator, PeriodicBudgetCodec.successor(previous, budget, debit, lower, upper));
    }

    /**
     * Adds the authenticated counter as a full script witness to preserve the four-reference bound.
     * The caller must separately attach the complete account/core/module transfer and its approvals.
     */
    public static Tx attach(Tx tx, Prepared prepared, byte[] intentDigest) {
        return tx.attachSpendingValidator(prepared.validator())
                .collectFrom(List.of(prepared.input()), PlutusDataAdapter.toClientLib(AccountCodec.data(new AssetRedeemer(BigInteger.ONE, intentDigest))))
                .payToContract(prepared.input().getAddress(), List.copyOf(prepared.input().getAmount()),
                        PlutusDataAdapter.toClientLib(AccountCodec.data(prepared.next())));
    }
}
