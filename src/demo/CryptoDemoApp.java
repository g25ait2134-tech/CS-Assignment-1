package demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class CryptoDemoApp {
    private final String mode;
    private final KeyPair rsaKeyPair;
    private final ToyRsa toyRsa;
    private final RateLimiter cryptoRateLimiter;
    private final SecureRandom secureRandom = new SecureRandom();

    private CryptoDemoApp(String mode) throws Exception {
        this.mode = mode;
        int keyBits = intEnv("RSA_KEY_BITS", 3072);
        if ("secure".equals(mode) && keyBits < 2048) {
            throw new IllegalArgumentException("secure mode requires RSA_KEY_BITS >= 2048");
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(keyBits, secureRandom);
        this.rsaKeyPair = generator.generateKeyPair();
        this.toyRsa = ToyRsa.create();
        this.cryptoRateLimiter = new RateLimiter(intEnv("MAX_CRYPTO_OPS_PER_MIN", 120));
        RSAPublicKey publicKey = (RSAPublicKey) rsaKeyPair.getPublic();
        log("startup", "rsaKeyBits", publicKey.getModulus().bitLength(),
                "secureEncryption", "RSA-OAEP-SHA256", "secureSignature", "RSASSA-PSS-SHA256");
    }

    public static void main(String[] args) throws Exception {
        String mode = env("APP_MODE", "secure").toLowerCase();
        int port = intEnv("PORT", 8080);
        CryptoDemoApp app = new CryptoDemoApp(mode);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/health", app::health);
        server.createContext("/secure/public-key", app::securePublicKey);
        server.createContext("/secure/encrypt", app::secureEncrypt);
        server.createContext("/secure/decrypt", app::secureDecrypt);
        server.createContext("/secure/sign", app::secureSign);
        server.createContext("/secure/verify", app::secureVerify);
        server.createContext("/token/issue", app::tokenIssue);
        server.createContext("/token/verify", app::tokenVerify);
        server.createContext("/toy/challenge", app::toyChallenge);
        server.setExecutor(Executors.newFixedThreadPool(intEnv("HTTP_THREADS", 8)));
        server.start();
        app.log("server_started", "port", port);
    }

    private void health(HttpExchange exchange) throws IOException {
        json(exchange, 200, DemoJson.object("status", "UP", "mode", mode));
    }

    private void securePublicKey(HttpExchange exchange) throws IOException {
        RSAPublicKey publicKey = (RSAPublicKey) rsaKeyPair.getPublic();
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(publicKey.getEncoded())
                + "\n-----END PUBLIC KEY-----";
        json(exchange, 200, DemoJson.object("algorithm", "RSA", "keyBits", publicKey.getModulus().bitLength(),
                "encryptionPadding", "RSA/ECB/OAEPWithSHA-256AndMGF1Padding",
                "signatureAlgorithm", "RSASSA-PSS SHA-256", "publicKeyPem", pem));
    }

    private void secureEncrypt(HttpExchange exchange) throws IOException {
        long start = System.nanoTime();
        String requestId = requestId(exchange);
        try {
            String plaintext = body(exchange).getOrDefault("plaintext", "");
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, rsaKeyPair.getPublic(), oaepSha256());
            String ciphertext = Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
            log("secure_encrypt_ok", "requestId", requestId, "padding", "OAEP-SHA256",
                    "plaintextBytes", plaintext.length(), "latencyMs", elapsedMs(start));
            json(exchange, 200, DemoJson.object("ciphertext", ciphertext));
        } catch (Exception e) {
            log("secure_encrypt_error", "requestId", requestId, "error", e.getClass().getSimpleName());
            json(exchange, 400, DemoJson.object("error", "encryption_failed"));
        }
    }

    private void secureDecrypt(HttpExchange exchange) throws IOException {
        long start = System.nanoTime();
        String requestId = requestId(exchange);
        if (!cryptoRateLimiter.allow()) {
            log("rate_limit_reject", "requestId", requestId, "endpoint", "/secure/decrypt",
                    "limitPerMinute", cryptoRateLimiter.limit());
            json(exchange, 429, DemoJson.object("error", "rate_limited"));
            return;
        }
        try {
            byte[] ciphertext = Base64.getDecoder().decode(body(exchange).getOrDefault("ciphertext", ""));
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.DECRYPT_MODE, rsaKeyPair.getPrivate(), oaepSha256());
            String plaintext = new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            log("secure_decrypt_ok", "requestId", requestId, "padding", "OAEP-SHA256",
                    "ciphertextBytes", ciphertext.length, "latencyMs", elapsedMs(start));
            json(exchange, 200, DemoJson.object("plaintext", plaintext));
        } catch (Exception e) {
            log("secure_decrypt_error", "requestId", requestId, "error", "opaque_decrypt_failure",
                    "latencyMs", elapsedMs(start));
            json(exchange, 400, DemoJson.object("error", "decryption_failed"));
        }
    }

    private void secureSign(HttpExchange exchange) throws IOException {
        long start = System.nanoTime();
        String requestId = requestId(exchange);
        if (!cryptoRateLimiter.allow()) {
            log("rate_limit_reject", "requestId", requestId, "endpoint", "/secure/sign",
                    "limitPerMinute", cryptoRateLimiter.limit());
            json(exchange, 429, DemoJson.object("error", "rate_limited"));
            return;
        }
        try {
            String payload = body(exchange).getOrDefault("payload", "");
            String signature = Base64.getEncoder().encodeToString(signPss(payload.getBytes(StandardCharsets.UTF_8)));
            log("secure_sign_ok", "requestId", requestId, "algorithm", "RSASSA-PSS-SHA256",
                    "payloadBytes", payload.length(), "latencyMs", elapsedMs(start));
            json(exchange, 200, DemoJson.object("signature", signature));
        } catch (Exception e) {
            log("secure_sign_error", "requestId", requestId, "error", e.getClass().getSimpleName());
            json(exchange, 400, DemoJson.object("error", "sign_failed"));
        }
    }

    private void secureVerify(HttpExchange exchange) throws IOException {
        long start = System.nanoTime();
        String requestId = requestId(exchange);
        try {
            Map<String, String> request = body(exchange);
            boolean valid = verifyPss(request.getOrDefault("payload", "").getBytes(StandardCharsets.UTF_8),
                    Base64.getDecoder().decode(request.getOrDefault("signature", "")));
            log("secure_verify_done", "requestId", requestId, "algorithm", "RSASSA-PSS-SHA256",
                    "valid", valid, "latencyMs", elapsedMs(start));
            json(exchange, valid ? 200 : 401, DemoJson.object("valid", valid));
        } catch (Exception e) {
            log("secure_verify_error", "requestId", requestId, "error", e.getClass().getSimpleName());
            json(exchange, 400, DemoJson.object("error", "verify_failed"));
        }
    }

    private void tokenIssue(HttpExchange exchange) throws IOException {
        long start = System.nanoTime();
        String requestId = requestId(exchange);
        try {
            Map<String, String> request = body(exchange);
            String sub = request.getOrDefault("sub", "alice");
            String role = request.getOrDefault("role", "user");
            String header = b64Url(DemoJson.object("alg", "PS256", "typ", "JWT").getBytes(StandardCharsets.UTF_8));
            String payload = b64Url(DemoJson.object("sub", sub, "role", role, "iat", Instant.now().toString()).getBytes(StandardCharsets.UTF_8));
            String signingInput = header + "." + payload;
            String token = signingInput + "." + b64Url(signPss(signingInput.getBytes(StandardCharsets.US_ASCII)));
            log("token_issue_ok", "requestId", requestId, "algorithm", "PS256", "sub", sub, "role", role, "latencyMs", elapsedMs(start));
            json(exchange, 200, DemoJson.object("token", token));
        } catch (Exception e) {
            log("token_issue_error", "requestId", requestId, "error", e.getClass().getSimpleName());
            json(exchange, 400, DemoJson.object("error", "token_issue_failed"));
        }
    }

    private void tokenVerify(HttpExchange exchange) throws IOException {
        long start = System.nanoTime();
        String requestId = requestId(exchange);
        try {
            String token = body(exchange).getOrDefault("token", "");
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) {
                log("token_verify_reject", "requestId", requestId, "reason", "bad_token_shape",
                        "latencyMs", elapsedMs(start));
                json(exchange, 400, DemoJson.object("error", "bad_token_shape"));
                return;
            }
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            String alg = DemoJson.parseFlat(headerJson).getOrDefault("alg", "");
            if ("vulnerable".equals(mode) && "none".equalsIgnoreCase(alg)) {
                log("vulnerable_token_bypass", "requestId", requestId, "trustedClientAlg", alg,
                        "result", "accepted_unsigned_admin_token", "latencyMs", elapsedMs(start));
                json(exchange, 200, DemoJson.object("valid", true, "acceptedBy", "vulnerable-alg-none", "payload", payloadJson));
                return;
            }
            if (!"PS256".equals(alg)) {
                log("token_verify_reject", "requestId", requestId, "reason", "alg_not_pinned",
                        "clientAlg", alg, "latencyMs", elapsedMs(start));
                json(exchange, 401, DemoJson.object("valid", false, "reason", "alg_not_allowed"));
                return;
            }
            boolean valid = verifyPss((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII),
                    Base64.getUrlDecoder().decode(parts[2]));
            log(valid ? "token_verify_done" : "token_verify_reject", "requestId", requestId,
                    "algorithm", "PS256", "valid", valid,
                    "reason", valid ? "signature_valid" : "signature_mismatch",
                    "latencyMs", elapsedMs(start));
            json(exchange, valid ? 200 : 401, DemoJson.object("valid", valid, "payload", valid ? payloadJson : ""));
        } catch (Exception e) {
            log("token_verify_error", "requestId", requestId, "error", e.getClass().getSimpleName());
            json(exchange, 400, DemoJson.object("error", "token_verify_failed"));
        }
    }

    private void toyChallenge(HttpExchange exchange) throws IOException {
        if (!"vulnerable".equals(mode)) {
            json(exchange, 404, DemoJson.object("error", "toy_rsa_disabled_in_secure_mode"));
            return;
        }
        String secret = env("TOY_SECRET", "RSA42");
        BigInteger message = new BigInteger(1, secret.getBytes(StandardCharsets.UTF_8));
        BigInteger ciphertext = message.modPow(toyRsa.e, toyRsa.n);
        log("toy_challenge_issued", "rsaBits", toyRsa.n.bitLength(), "publicN", toyRsa.n,
                "note", "small_key_factorable_for_lab_only");
        json(exchange, 200, DemoJson.object("ciphertext", ciphertext.toString(),
                "n", toyRsa.n.toString(), "e", toyRsa.e.toString()));
    }

    private byte[] signPss(byte[] payload) throws Exception {
        Signature signer = Signature.getInstance("RSASSA-PSS");
        signer.setParameter(pssSha256());
        signer.initSign(rsaKeyPair.getPrivate(), secureRandom);
        signer.update(payload);
        return signer.sign();
    }

    private boolean verifyPss(byte[] payload, byte[] signature) throws Exception {
        Signature verifier = Signature.getInstance("RSASSA-PSS");
        verifier.setParameter(pssSha256());
        verifier.initVerify(rsaKeyPair.getPublic());
        verifier.update(payload);
        return verifier.verify(signature);
    }

    private Map<String, String> body(HttpExchange exchange) throws IOException {
        return DemoJson.parseFlat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String requestId(HttpExchange exchange) {
        String id = exchange.getRequestHeaders().getFirst("x-request-id");
        return id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
    }

    private void log(String event, Object... pairs) {
        Object[] withMode = new Object[pairs.length + 2];
        withMode[0] = "mode";
        withMode[1] = mode;
        System.arraycopy(pairs, 0, withMode, 2, pairs.length);
        DemoLog.log(event, withMode);
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static PSSParameterSpec pssSha256() {
        return new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1);
    }

    private static OAEPParameterSpec oaepSha256() {
        return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    }

    private static String b64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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

    private record ToyRsa(BigInteger n, BigInteger e, BigInteger d) {
        static ToyRsa create() {
            BigInteger p = BigInteger.valueOf(4_000_000_007L);
            BigInteger q = BigInteger.valueOf(4_000_000_063L);
            BigInteger n = p.multiply(q);
            BigInteger phi = p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE));
            BigInteger e = BigInteger.valueOf(65_537L);
            return new ToyRsa(n, e, e.modInverse(phi));
        }
    }

    private static final class RateLimiter {
        private final int limit;
        private final AtomicInteger count = new AtomicInteger();
        private volatile long windowStartMs = System.currentTimeMillis();

        private RateLimiter(int limit) {
            this.limit = limit;
        }

        boolean allow() {
            long now = System.currentTimeMillis();
            if (now - windowStartMs > 60_000) {
                synchronized (this) {
                    if (now - windowStartMs > 60_000) {
                        windowStartMs = now;
                        count.set(0);
                    }
                }
            }
            return count.incrementAndGet() <= limit;
        }

        int limit() {
            return limit;
        }
    }
}
