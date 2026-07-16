package com.mishiranu.dashchan.chan.fourchan;

import android.net.Uri;

import chan.http.HttpException;
import chan.http.HttpRequest;
import chan.http.HttpResponse;

class HttpRequestUnsafeRedirectHandler implements HttpRequest.RedirectHandler {

    private HttpRequest.RedirectHandler delegate = null;

    HttpRequestUnsafeRedirectHandler() {

    }

    HttpRequestUnsafeRedirectHandler(HttpRequest.RedirectHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public Action onRedirect(HttpResponse response) throws HttpException {
        Uri originalUri = response.getRequestedUri();
        Uri redirectUri = response.getRedirectedUri();

        if (originalUri != null && redirectUri != null) {
            String originalScheme = originalUri.getScheme();
            String redirectScheme = redirectUri.getScheme();

            if (originalScheme != null && redirectScheme != null) {
                String HTTP_SCHEME = "http";
                String HTTPS_SCHEME = "https";

                boolean unsafeRedirect =
                        originalScheme.equals(HTTPS_SCHEME) &&
                                redirectScheme.equals(HTTP_SCHEME);

                if (unsafeRedirect) {
                    Uri httpsRedirectUri = redirectUri
                            .buildUpon()
                            .scheme(HTTPS_SCHEME)
                            .build();

                    response.setRedirectedUri(httpsRedirectUri);
                }
            }
        }

        if (delegate != null) {
            return delegate.onRedirect(response);
        } else {
            return HttpRequest.RedirectHandler.Action.RETRANSMIT;
        }
    }
}
