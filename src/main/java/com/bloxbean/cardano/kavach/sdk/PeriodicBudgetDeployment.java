package com.bloxbean.cardano.kavach.sdk;

import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.contracts.BudgetAccountAssetValidator;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetNftPolicy;
import com.bloxbean.cardano.kavach.contracts.PeriodicBudgetValidator;
import java.math.BigInteger;
import java.util.Arrays;

/** Derives the separate immutable asset profile and its per-account counter validator. */
public final class PeriodicBudgetDeployment {
    private PeriodicBudgetDeployment() {}
    /** Replaces only the asset script before genesis; never migrates an existing account or its assets. */
    public static AccountDeployment.Scripts derive(AccountDeployment.Scripts base, DeploymentDomain domain) throws CborSerializationException {
        var counter = validator(domain, base.accountId(), base.coreBinding());
        var asset = load(BudgetAccountAssetValidator.class, BigInteger.ONE, domain, base.accountId(),
                base.state().getScriptHash(), base.checkpoint().getScriptHash(), counter.getScriptHash());
        var binding = new CoreBinding(base.state().getScriptHash(), asset.getScriptHash(), base.checkpoint().getScriptHash());
        return new AccountDeployment.Scripts(base.state(), base.checkpoint(), base.module(), base.nft(), asset,
                base.accountId(), binding, base.authModule());
    }
    /** Derives the counter custody script from authenticated immutable account bindings. */
    public static PlutusV3Script validator(AccountState state) {
        return validator(state.deploymentDomain(), state.accountId(), state.coreBinding());
    }
    private static PlutusV3Script validator(DeploymentDomain domain, AccountId id, CoreBinding core) {
        return load(PeriodicBudgetValidator.class, BigInteger.ONE, domain, id, core.stateValidator(), core.checkpoint());
    }
    /** Verifies this local candidate's exact applied asset hash instead of trusting a dashboard flag. */
    public static boolean supports(AccountState state) throws CborSerializationException {
        var counter = validator(state);
        var asset = load(BudgetAccountAssetValidator.class, BigInteger.ONE, state.deploymentDomain(), state.accountId(),
                state.coreBinding().stateValidator(), state.coreBinding().checkpoint(), counter.getScriptHash());
        return Arrays.equals(asset.getScriptHash(), state.coreBinding().assetValidator());
    }
    /** Derives a creator-bound one-shot counter policy; administrative activation is a separate signed action. */
    public static PlutusV3Script mintPolicy(AccountState state, TxOutRef seed, byte[] creator) throws CborSerializationException {
        if (creator.length != 28 || !supports(state)) throw new IllegalArgumentException("Budget-capable account and creator hash required");
        return load(PeriodicBudgetNftPolicy.class, seed, creator, validator(state).getScriptHash());
    }
    private static PlutusV3Script load(Class<?> type, Object... values) {
        return JulcScriptLoader.load(type, Arrays.stream(values).map(AccountCodec::data).map(PlutusDataAdapter::toClientLib)
                .toArray(com.bloxbean.cardano.client.plutus.spec.PlutusData[]::new));
    }
}
