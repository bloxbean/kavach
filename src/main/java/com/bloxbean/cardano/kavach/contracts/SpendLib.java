package com.bloxbean.cardano.kavach.contracts;

import com.bloxbean.cardano.kavach.contracts.AccountLib;
import com.bloxbean.cardano.kavach.contracts.AccountTypes;
import com.bloxbean.cardano.kavach.contracts.NativeAccountingLib;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.AssetEntry;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;

/**
 * Exact partial-transfer semantics and bounded linear native-asset conservation. Authorization is checked by the calling core.
 */
@OnchainLibrary
public class SpendLib {
    /**
     * Verifies complete signed input/output sets, then accounts for every account asset.
     *
     * @param spend          untrusted partial-transfer action
     * @param accountAddress authenticated full account enterprise address
     * @param receipts       validated reward receipts that must remain disjoint from account allocations
     * @param ctx            ledger-supplied script context
     * @return whether the action satisfies exact input, allocation and complete value rules; authorization is checked separately
     */
    public static boolean validate(Spend spend, Address accountAddress, JulcList<RewardReceipt> receipts, ScriptContext ctx) {
        var tx = ctx.txInfo();
        BigInteger feeLimit = spend.maxAccountFee();
        if (spend.accountInputs().isEmpty() || AccountLib.listSize((JulcList<PlutusData>) (Object) spend.accountInputs()) > 8 || AccountLib.listSize((JulcList<PlutusData>) (Object) spend.recipients()) > 8
                || AccountLib.lessThan(feeLimit, BigInteger.ZERO) || AccountLib.lessThan(BigInteger.valueOf(5000000), feeLimit)
                || !AccountLib.shape((PlutusData) (Object) spend, 0, 3)
        ) return false;
        // A fixed two-byte mask covers the already bounded 0..15 output positions.
        // It is derived solely from the signed recipients, never supplied by the relayer.
        byte[] recipientMask = new byte[]{0, 0};
        boolean validIndices = true;
        for (var recipient : spend.recipients()) {
            var index = recipient.outputIndex();
            if (AccountLib.lessThan(index, BigInteger.ZERO) || AccountLib.atMost(BigInteger.valueOf(16), index))
                validIndices = false;
            else
                recipientMask = Builtins.orByteString(false, recipientMask, Builtins.shiftByteString(new byte[]{0, 1}, index.longValue()));
        }
        return validIndices && semantics(spend, accountAddress, receipts, recipientMask, ctx)
                && NativeAccountingLib.conserve(accountAddress, recipientMask, feeLimit, ctx);
    }

    /**
     * Matches the strictly ordered signed references against ledger-ordered consumed inputs.
     * Every input at the account payment credential must use its full enterprise address;
     * other script inputs reject. Recipients, account change and reward receipts are disjoint.
     * This linear merge relies on the ledger supplying inputs in canonical TxOutRef order.
     */
    static boolean semantics(Spend spend, Address accountAddress, JulcList<RewardReceipt> receipts, byte[] recipientMask, ScriptContext ctx) {
        boolean valid = true;
        int count = 0;
        JulcList<TxOutRef> remaining = spend.accountInputs();
        byte[] previousHash = new byte[]{};
        BigInteger previousIndex = BigInteger.valueOf(-1);
        for (TxOutRef ref : spend.accountInputs()) {
            byte[] hash = ref.txId().hash();
            if (!Builtins.lessThanByteString(previousHash, hash) && !(Builtins.equalsByteString(previousHash, hash) && AccountLib.lessThan(previousIndex, ref.index())))
                valid = false;
            previousHash = hash;
            previousIndex = ref.index();
        }
        for (var input : ctx.txInfo().inputs()) {
            if (input.resolved().address().credential().equals(accountAddress.credential())) {
                count = count + 1;
                if (!input.resolved().address().equals(accountAddress) || remaining.isEmpty()) valid = false;
                else {
                    if (!input.outRef().equals(remaining.head())) valid = false;
                    remaining = remaining.tail();
                }
            } else {
                boolean key = switch (input.resolved().address().credential()) {
                    case Credential.PubKeyCredential credential -> true;
                    default -> false;
                };
                if (!key) valid = false;
            }
        }
        BigInteger previous = BigInteger.valueOf(-1);
        for (var recipient : spend.recipients()) {
            BigInteger index = recipient.outputIndex();
            if (!AccountLib.index(index, ctx) || AccountLib.atMost(index, previous) || AccountLib.receiptAt(receipts, index)
                    || recipient.address().credential().equals(accountAddress.credential())
                    || !AccountLib.shape((PlutusData) (Object) recipient, 0, 3)) valid = false;
            else {
                var output = ctx.txInfo().outputs().get(index.intValue());
                if (!output.address().equals(recipient.address()) || !AccountLib.plain(output) || !exactValue(recipient.value(), output.value()))
                    valid = false;
            }
            previous = index;
        }
        int position = 0;
        for (var output : ctx.txInfo().outputs()) {
            boolean account = output.address().credential().equals(accountAddress.credential());
            boolean recipient = Builtins.readBit(recipientMask, position);
            if (account && (!output.address().equals(accountAddress) || !AccountLib.plain(output)
                    || AccountLib.receiptAt(receipts, BigInteger.valueOf(position)))) valid = false;
            if (!account && !recipient) {
                boolean key = switch (output.address().credential()) {
                    case Credential.PubKeyCredential credential -> true;
                    default -> false;
                };
                if (!key || !AccountLib.plain(output)) valid = false;
            }
            position = position + 1;
        }
        return valid && remaining.isEmpty() && count == AccountLib.listSize((JulcList<PlutusData>) (Object) spend.accountInputs());
    }

    /**
     * Exact signed asset list versus the entire ledger Value; absent and extra assets reject.
     */
    static boolean exactValue(JulcList<Asset> signed, Value actual) {
        if (AccountLib.listSize((JulcList<PlutusData>) (Object) signed) == 1) {
            BigInteger ada = NativeAccountingLib.onlyLovelace(actual);
            // The complete conservation pass checks every actual output quantity's uint63 bound.
            return AccountLib.lessThan(BigInteger.ZERO, ada)
                    && Builtins.equalsData((PlutusData) (Object) signed.head(), (PlutusData) (Object) new Asset(new byte[]{}, new byte[]{}, ada));
        }
        var entries = ValuesLib.flattenTyped(actual);
        if (signed.isEmpty() || AccountLib.listSize((JulcList<PlutusData>) (Object) signed) > 12 || AccountLib.listSize((JulcList<PlutusData>) (Object) signed) != AccountLib.listSize((JulcList<PlutusData>) (Object) entries))
            return false;
        boolean valid = true;
        int i = 0;
        byte[] previousPolicy = new byte[]{};
        byte[] previousName = new byte[]{};
        for (var asset : signed) {
            var entry = entries.get(i);
            if (!Builtins.equalsByteString(asset.policy(), entry.policyId()) || !Builtins.equalsByteString(asset.name(), entry.tokenName())
                    || !asset.quantity().equals(entry.amount()) || AccountLib.atMost(asset.quantity(), BigInteger.ZERO) || !AccountLib.uint63(asset.quantity())
                    || !AccountLib.shape((PlutusData) (Object) asset, 0, 3)) valid = false;
            if (i == 0) {
                if (Builtins.lengthOfByteString(asset.policy()) != 0 || Builtins.lengthOfByteString(asset.name()) != 0)
                    valid = false;
            } else {
                if (Builtins.lengthOfByteString(asset.policy()) != 28 || Builtins.lengthOfByteString(asset.name()) > 32
                        || (!Builtins.lessThanByteString(previousPolicy, asset.policy())
                        && !(Builtins.equalsByteString(previousPolicy, asset.policy()) && Builtins.lessThanByteString(previousName, asset.name()))))
                    valid = false;
            }
            previousPolicy = asset.policy();
            previousName = asset.name();
            i = i + 1;
        }
        return valid;
    }
}
