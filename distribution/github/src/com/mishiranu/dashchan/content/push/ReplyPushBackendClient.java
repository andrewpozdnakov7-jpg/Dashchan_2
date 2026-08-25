package com.mishiranu.dashchan.content.push;

import com.mishiranu.dashchan.BuildConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

final class ReplyPushBackendClient {
	private static final String APPLICATION_ID = "io.dashchan2";
	private static final int MAX_RESPONSE_BYTES = 32768;
	private static final Pattern SECRET_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

	public enum Kind { SUCCESS, TEMPORARY, AUTH_INVALID, CONFLICT, PERMANENT }

	public static final class Result {
		public final Kind kind;
		public final int retryAfterSeconds;
		public final String installationSecret;

		private Result(Kind kind, int retryAfterSeconds, String installationSecret) {
			this.kind = kind;
			this.retryAfterSeconds = retryAfterSeconds;
			this.installationSecret = installationSecret;
		}

		public static Result success(String installationSecret) {
			return new Result(Kind.SUCCESS, 0, installationSecret);
		}
	}

	private static final class Response {
		public final int status;
		public final int retryAfterSeconds;
		public final String body;

		private Response(int status, int retryAfterSeconds, String body) {
			this.status = status;
			this.retryAfterSeconds = retryAfterSeconds;
			this.body = body;
		}
	}

	public Result registerInstallation(String installationId, String firebaseRegistrationId, String secret) {
		if (!ReplyPushContract.isInstallationId(installationId)
				|| !isValidRegistrationId(firebaseRegistrationId)
				|| secret != null && !SECRET_PATTERN.matcher(secret).matches()) {
			return permanent();
		}
		try {
			JSONObject body = new JSONObject();
			body.put("fcm_token", firebaseRegistrationId);
			body.put("application_id", APPLICATION_ID);
			body.put("app_version", BuildConfig.VERSION_NAME);
			body.put("platform", "android");
			Response response = execute("PUT", "/v1/installations/" + installationId, secret, body);
			Result failure = classifyFailure(response);
			if (failure != null) {
				return failure;
			}
			if (response.status != HttpURLConnection.HTTP_OK
					&& response.status != HttpURLConnection.HTTP_CREATED) {
				return permanent();
			}
			JSONObject result = new JSONObject(response.body);
			boolean created = response.status == HttpURLConnection.HTTP_CREATED;
			Set<String> keys = created
					? setOf("installation_id", "created", "installation_secret", "installation_ttl_days")
					: setOf("installation_id", "created", "installation_ttl_days");
			if (!hasExactKeys(result, keys) || !installationId.equals(result.opt("installation_id"))
					|| !(result.opt("created") instanceof Boolean)
					|| ((Boolean) result.opt("created")) != created
					|| !(result.opt("installation_ttl_days") instanceof Number)
					|| ((Number) result.opt("installation_ttl_days")).intValue() != 90) {
				return permanent();
			}
			if (created) {
				Object value = result.opt("installation_secret");
				String installationSecret = value instanceof String ? (String) value : "";
				return SECRET_PATTERN.matcher(installationSecret).matches()
						? Result.success(installationSecret) : permanent();
			}
			return Result.success(null);
		} catch (IOException e) {
			return temporary(0);
		} catch (JSONException | RuntimeException e) {
			return permanent();
		}
	}

	public Result putWatch(String installationId, String secret, String chanName, String boardName,
			String threadNumber, String postNumber) {
		String watchId = ReplyPushContract.makeWatchId(installationId, chanName, boardName,
				threadNumber, postNumber);
		if (watchId == null || secret == null || !SECRET_PATTERN.matcher(secret).matches()) {
			return permanent();
		}
		try {
			JSONObject body = new JSONObject();
			body.put("chan_name", chanName);
			body.put("board", boardName);
			body.put("thread_id", threadNumber);
			body.put("post_id", postNumber);
			Response response = execute("PUT", "/v1/installations/" + installationId
					+ "/watches/" + watchId, secret, body);
			Result failure = classifyFailure(response);
			if (failure != null) {
				return failure;
			}
			if (response.status != HttpURLConnection.HTTP_OK
					&& response.status != HttpURLConnection.HTTP_CREATED) {
				return permanent();
			}
			JSONObject result = new JSONObject(response.body);
			boolean created = response.status == HttpURLConnection.HTTP_CREATED;
			if (!hasExactKeys(result, setOf("watch_id", "created", "expires_at"))
					|| !watchId.equals(result.opt("watch_id"))
					|| !(result.opt("created") instanceof Boolean)
					|| ((Boolean) result.opt("created")) != created) {
				return permanent();
			}
			Object value = result.opt("expires_at");
			String expiresAt = value instanceof String ? (String) value : "";
			return expiresAt.length() > 0 && expiresAt.length() <= 64 ? Result.success(null) : permanent();
		} catch (IOException e) {
			return temporary(0);
		} catch (JSONException | RuntimeException e) {
			return permanent();
		}
	}

	public Result deleteWatch(String installationId, String secret, String watchId) {
		if (!ReplyPushContract.isInstallationId(installationId) || secret == null
				|| !SECRET_PATTERN.matcher(secret).matches() || watchId == null
				|| !watchId.matches("[0-9a-f]{64}")) {
			return permanent();
		}
		try {
			Response response = execute("DELETE", "/v1/installations/" + installationId
					+ "/watches/" + watchId, secret, null);
			Result failure = classifyFailure(response);
			if (failure != null) {
				return failure;
			}
			return response.status == HttpURLConnection.HTTP_NO_CONTENT
					&& response.body.isEmpty() ? Result.success(null) : permanent();
		} catch (IOException e) {
			return temporary(0);
		}
	}

	public Result deleteInstallation(String installationId, String secret) {
		if (!ReplyPushContract.isInstallationId(installationId)
				|| secret == null || !SECRET_PATTERN.matcher(secret).matches()) {
			return permanent();
		}
		try {
			Response response = execute("DELETE", "/v1/installations/" + installationId,
					secret, null);
			Result failure = classifyFailure(response);
			if (failure != null) {
				return failure;
			}
			return response.status == HttpURLConnection.HTTP_NO_CONTENT
					&& response.body.isEmpty() ? Result.success(null) : permanent();
		} catch (IOException e) {
			return temporary(0);
		}
	}

	private static Response execute(String method, String path, String secret, JSONObject json)
			throws IOException {
		byte[] request = json != null ? json.toString().getBytes(StandardCharsets.UTF_8) : null;
		if (request != null && request.length > 16384) {
			throw new IOException("Request is too large");
		}
		HttpURLConnection connection = (HttpURLConnection) new URL(
				BuildConfig.SLOOOP_PUSH_BASE_URL + path).openConnection();
		connection.setConnectTimeout(15000);
		connection.setReadTimeout(15000);
		connection.setInstanceFollowRedirects(false);
		connection.setRequestMethod(method);
		connection.setRequestProperty("Accept", "application/json");
		if (secret != null) {
			connection.setRequestProperty("Authorization", "Bearer " + secret);
		}
		if (request != null) {
			connection.setDoOutput(true);
			connection.setFixedLengthStreamingMode(request.length);
			connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
			try (OutputStream output = connection.getOutputStream()) {
				output.write(request);
			}
		}
		try {
			int status = connection.getResponseCode();
			InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
			String body = input != null ? readResponse(input) : "";
			return new Response(status, parseRetryAfter(connection.getHeaderField("Retry-After")), body);
		} finally {
			connection.disconnect();
		}
	}

	private static String readResponse(InputStream input) throws IOException {
		try (InputStream closeable = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[4096];
			int total = 0;
			int count;
			while ((count = closeable.read(buffer)) >= 0) {
				total += count;
				if (total > MAX_RESPONSE_BYTES) {
					throw new IOException("Response is too large");
				}
				output.write(buffer, 0, count);
			}
			return new String(output.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private static Result classifyFailure(Response response) {
		switch (response.status) {
			case HttpURLConnection.HTTP_UNAUTHORIZED:
			case HttpURLConnection.HTTP_FORBIDDEN:
				return new Result(Kind.AUTH_INVALID, 0, null);
			case HttpURLConnection.HTTP_CONFLICT:
				return new Result(Kind.CONFLICT, 0, null);
			case 429:
			case HttpURLConnection.HTTP_INTERNAL_ERROR:
			case HttpURLConnection.HTTP_BAD_GATEWAY:
			case HttpURLConnection.HTTP_UNAVAILABLE:
			case HttpURLConnection.HTTP_GATEWAY_TIMEOUT:
				return temporary(response.retryAfterSeconds);
			case HttpURLConnection.HTTP_BAD_REQUEST:
			case HttpURLConnection.HTTP_ENTITY_TOO_LARGE:
			case HttpURLConnection.HTTP_UNSUPPORTED_TYPE:
				return permanent();
			default:
				return null;
		}
	}

	private static Result temporary(int retryAfterSeconds) {
		return new Result(Kind.TEMPORARY, retryAfterSeconds, null);
	}

	private static Result permanent() {
		return new Result(Kind.PERMANENT, 0, null);
	}

	private static int parseRetryAfter(String value) {
		try {
			if (value == null) {
				return 0;
			}
			long seconds = Long.parseLong(value.trim());
			return seconds > 0L ? (int) Math.min(Integer.MAX_VALUE, seconds) : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static Set<String> setOf(String... values) {
		return new HashSet<>(Arrays.asList(values));
	}

	private static boolean hasExactKeys(JSONObject object, Set<String> expected) {
		HashSet<String> actual = new HashSet<>();
		Iterator<String> iterator = object.keys();
		while (iterator.hasNext()) {
			actual.add(iterator.next());
		}
		return expected.equals(actual);
	}

	private static boolean isValidRegistrationId(String registrationId) {
		if (registrationId == null || registrationId.length() < 20 || registrationId.length() > 4096) {
			return false;
		}
		for (int i = 0; i < registrationId.length(); i++) {
			if (Character.isWhitespace(registrationId.charAt(i))) {
				return false;
			}
		}
		return true;
	}
}
