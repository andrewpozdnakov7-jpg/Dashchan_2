package com.mishiranu.dashchan.content.service.webview;

interface IRequestCallback {
	const int DIAG_LOAD = 1;
	const int DIAG_TIMEOUT = 2;
	const int DIAG_MAIN_FRAME_ERROR = 3;
	const int DIAG_MAIN_FRAME_HTTP_ERROR = 4;
	const int DIAG_TLS_ERROR = 5;
	const int DIAG_SCRIPT_ERROR = 6;
	const int DIAG_CREATE_FAILED = 7;
	const int DIAG_PROXY_UNSUPPORTED = 8;

	boolean onPageFinished(String uriString, String cookie, String title);
	boolean onLoad(String uriString);

	String onRecaptchaV2(String apiKey, boolean invisible, String referer);
	String onHcaptcha(String apiKey, String referer);
	oneway void onDiagnosticEvent(int event, int code);
}
