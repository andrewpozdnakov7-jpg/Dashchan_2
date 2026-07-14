package com.mishiranu.dashchan.content.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import androidx.fragment.app.Fragment;
import chan.util.StringUtils;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.UpdateFragment;
import com.mishiranu.dashchan.util.AndroidUtils;
import com.mishiranu.dashchan.util.NavigationUtils;
import com.mishiranu.dashchan.widget.ProgressDialog;
import java.util.ArrayList;

public class UpdateDialogHelper {
	private static final int ACTION_OPEN = 0;
	private static final int ACTION_SKIP = 1;
	private static final int ACTION_CLOSE = 2;
	private static boolean automaticCheckRunning = false;

	public static void checkManually(Fragment fragment) {
		if (!fragment.isAdded()) {
			return;
		}
		Context context = fragment.requireContext();
		ProgressDialog progressDialog = new ProgressDialog(context, null);
		progressDialog.setMessage(context.getString(R.string.checking_for_updates__ellipsis));
		final boolean[] completed = {false};
		final UpdateChecker.CheckTask[] taskHolder = {null};
		progressDialog.setOnDismissListener(dialog -> {
			if (!completed[0] && taskHolder[0] != null) {
				taskHolder[0].cancel();
			}
		});
		progressDialog.show();
		taskHolder[0] = UpdateChecker.checkAsync(context, result -> {
			completed[0] = true;
			progressDialog.dismiss();
			if (fragment.isAdded()) {
				showResult(fragment.requireContext(), result, true);
			}
		});
	}

	public static void checkAutomatically(Activity activity) {
		if (automaticCheckRunning) {
			return;
		}
		automaticCheckRunning = true;
		UpdateChecker.checkAsync(activity, result -> {
			automaticCheckRunning = false;
			if (!activity.isFinishing() && !activity.isDestroyed() &&
					UpdateChecker.shouldShowAutomatically(result)) {
				showResult(activity, result, false);
			}
		});
	}

	private static void showResult(Context context, UpdateResult result, boolean manual) {
		if (result == null) {
			return;
		}
		switch (result.status) {
			case NO_UPDATE: {
				if (manual) {
					new AlertDialog.Builder(context)
							.setTitle(R.string.check_for_updates)
							.setMessage(R.string.latest_version_installed)
							.setPositiveButton(android.R.string.ok, null)
							.show();
				}
				return;
			}
			case ERROR: {
				if (manual) {
					new AlertDialog.Builder(context)
							.setTitle(R.string.update_check_failed)
							.setMessage(!StringUtils.isEmpty(result.errorMessage)
									? result.errorMessage : context.getString(R.string.unknown_error))
							.setPositiveButton(android.R.string.ok, null)
							.show();
				}
				return;
			}
			case UPDATE_AVAILABLE:
			case UPDATE_UNAVAILABLE:
			case RELEASE_FOUND: {
				showUpdateDialog(context, result);
				return;
			}
		}
	}

	private static void showUpdateDialog(Context context, UpdateResult result) {
		int versionCode = result.getPreferenceVersionCode();
		Preferences.setUpdateLastSeenVersionCode(versionCode);
		String title = result.title;
		if (StringUtils.isEmpty(title)) {
			title = context.getString(result.status == UpdateResult.Status.RELEASE_FOUND
					? R.string.update_found_on_github : R.string.update_available);
		}
		String message = buildMessage(context, result);
		ArrayList<String> labels = new ArrayList<>();
		ArrayList<Integer> actions = new ArrayList<>();
		if (result.hasOpenPageUrl()) {
			labels.add(context.getString(R.string.open_download_page));
			actions.add(ACTION_OPEN);
		}
		labels.add(context.getString(R.string.skip_this_version));
		actions.add(ACTION_SKIP);
		labels.add(context.getString(android.R.string.cancel));
		actions.add(ACTION_CLOSE);
		AlertDialog.Builder builder = new AlertDialog.Builder(context)
				.setTitle(title)
				.setMessage(message)
				.setItems(labels.toArray(new String[0]), (dialog, which) -> {
					switch (actions.get(which)) {
						case ACTION_OPEN: {
							openDownloadPage(context, result);
							break;
						}
						case ACTION_SKIP: {
							Preferences.setUpdateSkippedVersionCode(versionCode);
							break;
						}
						case ACTION_CLOSE:
						default: {
							break;
						}
					}
				});
		if (context instanceof FragmentHandler) {
			builder.setPositiveButton(R.string.open_updates, (dialog, which) ->
					((FragmentHandler) context).pushFragment(new UpdateFragment()));
		}
		builder.show();
	}

	private static String buildMessage(Context context, UpdateResult result) {
		StringBuilder builder = new StringBuilder();
		if (result.versionCode > 0 || !StringUtils.isEmpty(result.versionName)) {
			String versionName = !StringUtils.isEmpty(result.versionName)
					? result.versionName : BuildConfig.VERSION_NAME;
			if (result.versionCode > 0) {
				builder.append(context.getString(R.string.update_version__format,
						versionName, result.versionCode));
			} else {
				builder.append(versionName);
			}
		}
		if (!StringUtils.isEmpty(result.summary)) {
			appendParagraph(builder, result.summary);
		}
		if (result.status == UpdateResult.Status.UPDATE_UNAVAILABLE) {
			appendParagraph(builder, context.getString(R.string.update_unavailable_min_sdk__format,
					result.minSdk, Build.VERSION.SDK_INT));
		} else if (result.status == UpdateResult.Status.RELEASE_FOUND) {
			appendParagraph(builder, context.getString(R.string.update_github_unverified_version__sentence));
		}
		if (builder.length() == 0) {
			builder.append(AndroidUtils.getApplicationLabel(context));
		}
		return builder.toString();
	}

	private static void appendParagraph(StringBuilder builder, String text) {
		if (builder.length() > 0) {
			builder.append("\n\n");
		}
		builder.append(text);
	}

	private static void openDownloadPage(Context context, UpdateResult result) {
		String url = result.getOpenPageUrl();
		if (!StringUtils.isEmpty(url)) {
			NavigationUtils.handleUri(context, null, Uri.parse(url), NavigationUtils.BrowserType.EXTERNAL);
		}
	}
}
