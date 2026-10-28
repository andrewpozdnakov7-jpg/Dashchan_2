package com.mishiranu.dashchan.chan.e444;

import android.net.Uri;
import chan.content.Chan;
import chan.content.InvalidResponseException;
import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class E444RequestPerformer {
    @FunctionalInterface
    public interface RequestConfigurator {
        HttpRequest configure(HttpRequest request);
    }

    private final HttpRequest.Preset preset;
    private final String[] pathParts;
    private final LinkedHashMap<String, String> queryParameters = new LinkedHashMap<>();
    private RequestConfigurator requestConfigurator;

    private E444RequestPerformer(HttpRequest.Preset preset, String... pathParts) {
        this.preset = preset;
        this.pathParts = pathParts;
    }

    public static E444RequestPerformer request(HttpRequest.Preset preset, String... pathParts) {
        return new E444RequestPerformer(preset, pathParts);
    }

    public E444RequestPerformer param(String key, String value) {
        queryParameters.put(key, value);
        return this;
    }

    public E444RequestPerformer configure(RequestConfigurator requestConfigurator) {
        this.requestConfigurator = requestConfigurator;
        return this;
    }

    public HttpResponse perform() throws HttpException {
        HttpException lastSocketException = null;
        Chan site = Chan.get("e444");
        String first = "ech.ist".equals(site.locator.getPreferredHost()) ? "ech.ist" : "ech.bz";
        for (String host : new String[] {first, "ech.bz".equals(first) ? "ech.ist" : "ech.bz"}) {
            try {
                Uri.Builder uriBuilder = new Uri.Builder().scheme("https").authority(host);
                for (String pathPart : pathParts) {
                    uriBuilder.appendEncodedPath(pathPart);
                }
                for (Map.Entry<String, String> queryParameter : queryParameters.entrySet()) {
                    uriBuilder.appendQueryParameter(queryParameter.getKey(), queryParameter.getValue());
                }
                HttpRequest request = new HttpRequest(uriBuilder.build(), preset);
                request.addCookie("passcode_auth", site.configuration.getCookie("passcode_auth"))
                        .addCookie("usercode_auth", site.configuration.getCookie("usercode_auth"));
                if (requestConfigurator != null) {
                    request = requestConfigurator.configure(request);
                }
                return request.perform();
            } catch (HttpException e) {
                if (e.isSocketException() && requestConfigurator == null) {
                    lastSocketException = e;
                } else {
                    throw e;
                }
            }
        }
        if (lastSocketException != null) {
            throw lastSocketException;
        }
        throw new HttpException(0, "No ECH host is reachable");
    }

    public <T> T performJson(Class<T> valueClass) throws HttpException, InvalidResponseException {
        try {
            return E444JsonUtils.fromJson(perform().readString(), valueClass);
        } catch (IOException e) {
            throw new InvalidResponseException(e);
        }
    }
}
