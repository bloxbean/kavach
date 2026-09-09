package com.bloxbean.cardano.kavach.protocol;

import com.bloxbean.cardano.julc.core.PlutusData;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.math.BigInteger;

import com.bloxbean.cardano.julc.ledger.Value;

final class WireFixtures {
    static PlutusData bytes(int length, int fill) {
        var value = new byte[length];
        Arrays.fill(value, (byte) fill);
        return PlutusData.bytes(value);
    }

    static PlutusData number(long n) {
        return PlutusData.integer(n);
    }

    static PlutusData rec(PlutusData... fields) {
        return PlutusData.constr(0, fields);
    }

    static PlutusData list(PlutusData... fields) {
        return new PlutusData.ListData(List.of(fields));
    }

    private static final String[] PUBLIC_KEYS = {"b060cdbd3fe1625b92d04736ad0aa3d061c023f8353fdca377e1b9b73154441f", "af17af62b0cc23e8f8da2e3d26ff82ec733866a1a5320f99bf75f2b0ce8b446e", "90a88623b94d936224aa6f9a8023497a2d507b2243b74ba963b011e79e627e94"};

    static PlutusData key(int id) {
        return rec(number(id), PlutusData.bytes(HexFormat.of().parseHex(PUBLIC_KEYS[id])));
    }

    static PlutusData policy(int id) {
        return rec(number(1), list(number(id)));
    }

    static PlutusData config() {
        return rec(number(1), list(key(0), key(1), key(2)), policy(0), policy(1), policy(0), policy(1), policy(2), policy(1));
    }

    static PlutusData module() {
        return rec(bytes(28, 7), number(1));
    }

    static PlutusData account() {
        return rec(bytes(28, 1), bytes(0, 0));
    }

    static PlutusData deployment() {
        return rec(number(0), number(42), bytes(32, 2));
    }

    static PlutusData core() {
        return rec(bytes(28, 3), bytes(28, 4), bytes(28, 5));
    }

    static PlutusData input(int index) {
        return rec(bytes(32, 6), number(index));
    }

    static PlutusData domain() {
        return rec(number(1), deployment(), account(), core(), number(0), input(0));
    }

    static PlutusData address() {
        return rec(rec(bytes(28, 9)), PlutusData.constr(1));
    }

    static PlutusData value() {
        return list(rec(bytes(0, 0), bytes(0, 0), number(2_000_000)));
    }

    static PlutusData action(int tag) {
        return switch (tag) {
            case 0 -> rec(list(input(1)), list(rec(number(0), address(), value())), number(500_000));
            case 1 -> PlutusData.constr(1, config());
            case 2 -> PlutusData.constr(2, rec(bytes(28, 8), number(1)), config());
            case 3, 4 -> PlutusData.constr(tag);
            case 5, 7 -> PlutusData.constr(tag, number(1), config());
            case 8 ->
                    PlutusData.constr(8, input(1), number(0), address(), PlutusData.bytes(WireFormat.ledgerValueDigest(wholeValue())));
            case 6 -> PlutusData.constr(tag, number(1), bytes(32, 8));
            default -> throw new IllegalArgumentException();
        };
    }

    static PlutusData envelope(int action) {
        return rec(PlutusData.bytes(WireFormat.protocolTag()), domain(), rec(number(1000), number(2000)), action(action));
    }

    static PlutusData wholeValue() {
        return Value.lovelace(BigInteger.valueOf(2_000_000)).toPlutusData();
    }

    static PlutusData state() {
        return rec(number(1), account(), deployment(), core(), number(0), module(), config(), number(0), number(86_400_000), number(3_600_000), number(0), rec());
    }

    static PlutusData replace(PlutusData data, int index, PlutusData field) {
        var c = (PlutusData.ConstrData) data;
        var fields = new ArrayList<>(c.fields());
        fields.set(index, field);
        return new PlutusData.ConstrData(c.tag(), fields);
    }

    static PlutusData completion() {
        return replace(envelope(7), 1, replace(domain(), 4, number(1)));
    }

    static PlutusData pendingState(byte[] commitment) {
        return replace(replace(replace(state(), 4, number(1)), 7, number(1)), 11,
                PlutusData.constr(2, PlutusData.bytes(commitment), number(86402000), config()));
    }

    private WireFixtures() {
    }
}
