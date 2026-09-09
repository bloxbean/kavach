import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Properties;
import com.bloxbean.cardano.kavach.sdk.browser.BrowserSignatures;
/** Cross-language check against the existing, compiled Kavach adapter; no ledger writes. */
class VerifyKavachResponse {
    public static void main(String[] args) throws Exception {
        var p = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[0]))) { p.load(reader); }
        var hex = HexFormat.of();
        var key = hex.parseHex(p.getProperty("publicKey"));
        var digest = hex.parseHex(p.getProperty("digest"));
        var proof = BrowserSignatures.fromCip30(key, digest, p.getProperty("signature"), p.getProperty("key"), 0);
        if (!BrowserSignatures.verify(key, digest, proof, 0)) throw new AssertionError("Swift COSE rejected");
        System.out.println("Swift COSE accepted by Kavach BrowserSignatures.fromCip30 and verify (network 0). No ledger submission.");
    }
}
