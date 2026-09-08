package com.bloxbean.cardano.kavach.protocol;

import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.kavach.contracts.AccountTypes.*;
import com.bloxbean.cardano.kavach.sdk.AccountCodec;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/** Compares independently assembled Java protocol records with the frozen language-neutral vectors. */
class AccountCodecConformanceTest {
    private static byte[] bytes(int length, int fill) { byte[] b = new byte[length]; Arrays.fill(b, (byte) fill); return b; }
    private static BigInteger n(long value) { return BigInteger.valueOf(value); }
    private final DeploymentDomain deployment = new DeploymentDomain(n(0), n(42), bytes(32, 2));
    private final AccountId account = new AccountId(bytes(28, 1), new byte[0]);
    private final CoreBinding core = new CoreBinding(bytes(28, 3), bytes(28, 4), bytes(28, 5));
    private final AuthModuleRef module = new AuthModuleRef(bytes(28, 7), n(1));
    private final Address recipient = new Address(new Credential.PubKeyCredential(new PubKeyHash(bytes(28, 9))), Optional.empty());
    private TxOutRef ref(int index) { return new TxOutRef(new TxId(bytes(32, 6)), n(index)); }
    private Action action(int tag) {
        return switch (tag) {
            case 0 -> new Spend(JulcList.of(ref(1)), JulcList.of(new Recipient(n(0), recipient,
                    JulcList.of(new Asset(new byte[0], new byte[0], n(2000000))))), n(500000));
            case 1 -> new ReplaceConfig(WireFixtures.config());
            case 2 -> new ReplaceModule(new AuthModuleRef(bytes(28, 8), n(1)), WireFixtures.config());
            case 3 -> new Freeze();
            case 4 -> new Unfreeze();
            case 5 -> new StartRecovery(n(1), WireFixtures.config());
            case 6 -> new CancelRecovery(n(1), bytes(32, 8));
            case 7 -> new CompleteRecovery(n(1), WireFixtures.config());
            case 8 -> new TransferWholeUtxo(ref(1), n(0), recipient, WireFormat.ledgerValueDigest(WireFixtures.wholeValue()));
            default -> throw new AssertionError(tag);
        };
    }
    @Test void allActionTagsMatchFrozenBytesDigestsAndRendering() throws Exception {
        var vectors = JsonUtil.parseJson(Files.readString(Path.of("conformance/v1/intent-vectors.json")));
        assertEquals(9, vectors.size());
        for (var vector : vectors) {
            int tag = vector.get("actionTag").asInt();
            var intent = new IntentEnvelope(WireFormat.protocolTag(), new IntentDomain(n(1), deployment, account, core, n(0), ref(0)),
                    new Validity(n(1000), n(2000)), action(tag));
            var encoded = AccountCodec.data(intent);
            var input = tag == 8 ? WireFixtures.wholeValue() : null;
            assertEquals(vector.get("cbor").asText(), PlutusDataAdapter.toClientLib(encoded).serializeToHex(), "action " + tag);
            assertEquals(vector.get("digest").asText(), HexFormat.of().formatHex(AccountCodec.intentDigest(intent, input)));
            assertEquals(vector.get("rendering").asText(), WireFormat.renderIntent(encoded, input));
        }
    }
    @Test void completeStateAndGenesisDomainMatchFrozenFixtures() throws Exception {
        var state = new AccountState(n(1), account, deployment, core, n(0), module, WireFixtures.config(), n(0), n(86400000), n(3600000), n(0), new Normal());
        assertEquals(Files.readString(Path.of("conformance/v1/state.hex")).trim(), PlutusDataAdapter.toClientLib(AccountCodec.data(state)).serializeToHex());
        var envelope = new GenesisProofEnvelope("KAVACH_GENESIS_POSSESSION_V1".getBytes(StandardCharsets.UTF_8),
                deployment, account, core, module, WireFormat.digest(state.authConfig()));
        assertEquals(ProofDomains.genesis(WireFixtures.state()), AccountCodec.data(envelope));
    }
    @Test void encodingIsExplicitAndDoesNotAliasByteArrayInputs() {
        byte[] value = {1, 2, 3}; var encoded = AccountCodec.data(value); value[0] = 9;
        assertEquals(PlutusData.bytes(new byte[]{1, 2, 3}), encoded);
        assertThrows(IllegalArgumentException.class, () -> AccountCodec.data(new Object()));
        assertEquals(PlutusData.constr(0, PlutusData.integer(1)), AccountCodec.data(Optional.of(n(1))));
        assertEquals(PlutusData.constr(1), AccountCodec.data(Optional.empty()));
    }
}
