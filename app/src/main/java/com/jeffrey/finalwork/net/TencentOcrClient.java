package com.jeffrey.finalwork.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TencentOcrClient {

    public static class OcrResult {
        public String name = "";
        public String idNumber = "";
        public String address = "";
        public String sex = "";
        public String nation = "";
        public String birth = "";
        public String rawJson = "";
    }

    public interface Callback {
        void onSuccess(OcrResult result);
        void onError(String msg);
    }

    private static final String HOST = "ocr.tencentcloudapi.com";
    private static final String SERVICE = "ocr";
    private static final String ACTION = "IDCardOCR";
    private static final String VERSION = "2018-11-19";
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";

    private final String secretId;
    private final String secretKey;
    private final String region;
    private final OkHttpClient http = new OkHttpClient();

    public TencentOcrClient(String secretId, String secretKey, String region) {
        this.secretId = secretId;
        this.secretKey = secretKey;
        this.region = region;
    }

    public void idCardOcr(String imageBase64, String cardSide, String configJson, Callback cb) {
        long ts = System.currentTimeMillis() / 1000;

        JsonObject body = new JsonObject();
        body.addProperty("ImageBase64", imageBase64);
        body.addProperty("CardSide", cardSide);
        if (configJson != null && !configJson.isEmpty()) body.addProperty("Config", configJson);

        String payload = body.toString();

        String authorization = Tc3Signer.buildAuthorization(
                secretId, secretKey, SERVICE, HOST, ACTION, payload, CONTENT_TYPE, ts
        );

        Request request = new Request.Builder()
                .url("https://" + HOST)
                .post(RequestBody.create(payload, MediaType.parse(CONTENT_TYPE)))
                .addHeader("Authorization", authorization)
                .addHeader("Content-Type", CONTENT_TYPE)
                .addHeader("Host", HOST)
                .addHeader("X-TC-Action", ACTION)
                .addHeader("X-TC-Timestamp", String.valueOf(ts))
                .addHeader("X-TC-Version", VERSION)
                .addHeader("X-TC-Region", region)
                .build();

        http.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
                cb.onError(e.getMessage());
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String resp = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    cb.onError("HTTP " + response.code() + " " + resp);
                    return;
                }
                try {
                    JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
                    JsonObject rsp = root.getAsJsonObject("Response");
                    if (rsp == null) {
                        cb.onError("Invalid JSON: no Response");
                        return;
                    }
                    if (rsp.has("Error")) {
                        cb.onError("Tencent Error: " + rsp.get("Error").toString());
                        return;
                    }

                    OcrResult r = new OcrResult();
                    r.rawJson = resp;
                    r.name = getStr(rsp, "Name");
                    r.idNumber = getStr(rsp, "IdNum");
                    r.address = getStr(rsp, "Address");
                    r.sex = getStr(rsp, "Sex");
                    r.nation = getStr(rsp, "Nation");
                    r.birth = getStr(rsp, "Birth");

                    cb.onSuccess(r);
                } catch (Exception ex) {
                    cb.onError("Parse error: " + ex.getMessage() + " raw=" + resp);
                }
            }
        });
    }

    private static String getStr(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }
}