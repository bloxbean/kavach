package com.bloxbean.cardano.kavach.demo;

import static org.junit.jupiter.api.Assertions.*;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.cip.cip30.CIP30DataSigner;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies the public-key export API against Yano's CCL signer without funding or a node. */
class WalletEnrollmentTest {
    @Test
    void verifiesCclExportAndRejectsAnotherPayloadOrNetwork() throws Exception {
        var service = new DemoService();
        var account = new Account(Networks.testnet());
        byte[] payload = MessageDigest.getInstance("SHA-256").digest(
                "Kavach demo public key export v1".getBytes(StandardCharsets.UTF_8));
        var response = CIP30DataSigner.INSTANCE.signData(new Address(account.baseAddress()).getBytes(), payload, account);
        var result = (Map<?, ?>) service.enrollment(Map.of("signature", response.signature(), "key", response.key()));
        assertEquals(HexUtil.encodeHexString(account.publicKeyBytes()), result.get("publicKey"));
        payload[0] ^= 1;
        var wrongPayload = CIP30DataSigner.INSTANCE.signData(new Address(account.baseAddress()).getBytes(), payload, account);
        assertThrows(IllegalArgumentException.class, () -> service.enrollment(
                Map.of("signature", wrongPayload.signature(), "key", wrongPayload.key())));
        payload[0] ^= 1;
        var mainnet = new Account(Networks.mainnet());
        var wrongNetwork = CIP30DataSigner.INSTANCE.signData(new Address(mainnet.baseAddress()).getBytes(), payload, mainnet);
        assertThrows(IllegalArgumentException.class, () -> service.enrollment(
                Map.of("signature", wrongNetwork.signature(), "key", wrongNetwork.key())));
    }
}
