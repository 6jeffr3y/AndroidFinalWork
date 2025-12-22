package com.jeffrey.finalwork.net;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Tc3Signer {

    private static final String ALGORITHM = "TC3-HMAC-SHA256";
    private static final String TERMINATION = "tc3_request";

    private Tc3Signer() {}

    public static String buildAuthorization(
            String secretId, String secretKey,
            String service, String host,
            String action,
            String payload,
            String contentType,
            long timestampSeconds
    ) {
        String date = utcDate(timestampSeconds);

        String canonicalRequest = buildCanonicalRequest(host, action, payload, contentType);
        String credentialScope = date + "/" + service + "/" + TERMINATION;

        String stringToSign = ALGORITHM + "\n"
                + timestampSeconds + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest);

        byte[] secretDate = hmacSha256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmacSha256(secretDate, service);
        byte[] secretSigning = hmacSha256(secretService, TERMINATION);

        String signature = bytesToHex(hmacSha256(secretSigning, stringToSign));

        return ALGORITHM + " "
                + "Credential=" + secretId + "/" + credentialScope + ", "
                + "SignedHeaders=content-type;host;x-tc-action, "
                + "Signature=" + signature;
    }

    private static String buildCanonicalRequest(String host, String action, String payload, String contentType) {
        String httpRequestMethod = "POST";
        String canonicalUri = "/";
        String canonicalQueryString = "";

        String canonicalHeaders =
                "content-type:" + contentType + "\n" +
                        "host:" + host.toLowerCase(Locale.ROOT) + "\n" +
                        "x-tc-action:" + action.toLowerCase(Locale.ROOT) + "\n";

        String signedHeaders = "content-type;host;x-tc-action";
        String hashedRequestPayload = sha256Hex(payload);

        return httpRequestMethod + "\n" +
                canonicalUri + "\n" +
                canonicalQueryString + "\n" +
                canonicalHeaders + "\n" +
                signedHeaders + "\n" +
                hashedRequestPayload;
    }

    private static String utcDate(long timestampSeconds) {
        Date date = new Date(timestampSeconds * 1000);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(date);
    }

    private static byte[] hmacSha256(byte[] key, String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 error", e);
        }
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return bytesToHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 error", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}