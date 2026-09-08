package com.bloxbean.cardano.kavach.protocol;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import org.junit.jupiter.api.Test;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static com.bloxbean.cardano.kavach.protocol.WireFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class WireBoundaryTest {
    private PlutusData replace(PlutusData data, int index, PlutusData field) {
        var c = (PlutusData.ConstrData) data; var fields = new ArrayList<>(c.fields()); fields.set(index, field);
        return new PlutusData.ConstrData(c.tag(), fields);
    }
    @Test void fullRegistryAndRoleSizesFitConfigStateAndIntentCaps() throws Exception {
        var keys = new ArrayList<PlutusData>();
        for (int i = 0; i < 16; i++) {
            var encoded = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded();
            keys.add(rec(number(i), PlutusData.bytes(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length))));
        }
        var first = rec(number(8), list(number(0),number(1),number(2),number(3),number(4),number(5),number(6),number(7)));
        var defensive = rec(number(8), list(number(8),number(9),number(10),number(11),number(12),number(13),number(14),number(15)));
        var config = rec(number(1), new PlutusData.ListData(keys), first, defensive, first, defensive, first, defensive);
        WireFormat.validateConfig(config); WireFormat.validateState(replace(state(), 6, config));
        var pending = replace(replace(replace(state(), 4, number(1)), 7, number(1)), 6, config);
        WireFormat.validateState(replace(pending, 11, PlutusData.constr(2, bytes(32,1), number(86402000), config)));
        WireFormat.renderIntent(replace(envelope(2), 3, PlutusData.constr(2, module(), config)));
        assertTrue(Builtins.serialiseData(config).length <= WireFormat.MAX_CONFIG_BYTES);
        keys.add(rec(number(16), bytes(32, 1)));
        assertThrows(IllegalArgumentException.class, () -> WireFormat.validateConfig(replace(config, 1, new PlutusData.ListData(keys))));
    }
    @Test void maximumCountsAndOneOverAreUnambiguous() {
        var inputs = new ArrayList<PlutusData>(); var recipients = new ArrayList<PlutusData>();
        for (int i = 0; i < 8; i++) { inputs.add(input(i + 1)); recipients.add(rec(number(i), address(), value())); }
        var spend = rec(new PlutusData.ListData(inputs), new PlutusData.ListData(recipients), number(5_000_000));
        WireFormat.renderIntent(replace(envelope(0), 3, spend));
        inputs.add(input(9));
        assertThrows(IllegalArgumentException.class, () -> WireFormat.renderIntent(replace(envelope(0), 3, replace(spend, 0, new PlutusData.ListData(inputs)))));
        recipients.add(rec(number(8), address(), value()));
        assertThrows(IllegalArgumentException.class, () -> WireFormat.renderIntent(replace(envelope(0), 3, replace(spend, 1, new PlutusData.ListData(recipients)))));
        var assets = new ArrayList<PlutusData>(); assets.add(rec(bytes(0,0), bytes(0,0), number(2_000_000)));
        for (int i = 1; i < 12; i++) assets.add(rec(bytes(28,i), bytes(0,0), number(1)));
        var supported = replace(envelope(0), 3, rec(list(input(1)), list(rec(number(0), address(), new PlutusData.ListData(assets))), number(0)));
        WireFormat.renderIntent(supported);
        assets.add(rec(bytes(28,12), bytes(0,0), number(1)));
        assertThrows(IllegalArgumentException.class, () -> WireFormat.renderIntent(replace(envelope(0), 3,
                rec(list(input(1)), list(rec(number(0), address(), new PlutusData.ListData(assets))), number(0)))));
        assertThrows(IllegalArgumentException.class, () -> WireFormat.renderIntent(replace(envelope(0), 3,
                rec(list(input(1)), list(rec(number(16), address(), value())), number(0)))));
    }
}
