package com.mishiranu.dashchan.ui.preference;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.FileProvider;
import com.mishiranu.dashchan.content.security.AppSecurity;
import com.mishiranu.dashchan.content.security.PrivacyReset;
import com.mishiranu.dashchan.content.security.ProfileGeneration;
import com.mishiranu.dashchan.content.security.SecurityStore;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import com.mishiranu.dashchan.util.CrashReports;
import com.mishiranu.dashchan.widget.MotionDialogBuilder;
import java.io.IOException;

public final class CrashReportDialogs {
	private CrashReportDialogs() {}
	private static Bundle arguments(FragmentActivity host, String id) throws IOException {
		Bundle args = new Bundle(); args.putString("id", id); args.putLong("profile", ProfileGeneration.current(host)); return args;
	}
	public static void offer(FragmentActivity host, String id) throws IOException {
		Offer dialog = new Offer(); dialog.setArguments(arguments(host, id));
		dialog.showNow(host.getSupportFragmentManager(), "crash-report-offer");
	}
	public static void view(FragmentActivity host, String id) {
		try {
			Viewer dialog = new Viewer(); dialog.setArguments(arguments(host, id));
			dialog.showNow(host.getSupportFragmentManager(), "crash-report-viewer");
		} catch (IOException e) { Toast.makeText(host, R.string.crash_report_unavailable, Toast.LENGTH_SHORT).show(); }
	}
	public static void confirmTest(FragmentActivity host) {
		try {
			Test dialog = new Test(); dialog.setArguments(arguments(host, ""));
			dialog.showNow(host.getSupportFragmentManager(), "crash-report-test");
		} catch (IOException e) { Toast.makeText(host, R.string.crash_report_unavailable, Toast.LENGTH_SHORT).show(); }
	}
	/** Restorable dialogs keep identifiers only, never the report text or an authorization lease. */
	public abstract static class ProtectedDialog extends DialogFragment {
		private boolean sharing;
		@Override public void onCreate(Bundle state) {
			super.onCreate(state); com.mishiranu.dashchan.ui.CrashReportPrompt.reportInteraction();
		}
		boolean usable() {
			if (!isAdded() || getActivity() == null || requireActivity().isFinishing() || requireActivity().isDestroyed()
					|| getParentFragmentManager().isStateSaved() || PrivacyReset.requiresRecovery(requireContext())
					|| SecurityStore.locked(requireContext())) return false;
			try { return ProfileGeneration.current(requireContext()) == requireArguments().getLong("profile", -1); }
			catch (IOException e) { return false; }
		}
		@Override public void onStart() { super.onStart(); AppSecurity.protectDialog(requireDialog()); }
		@Override public void onResume() {
			super.onResume();
			if (!usable()) { if (getDialog() != null) getDialog().hide(); }
			else if (getDialog() != null) getDialog().show();
		}
		void unavailable() { if (isAdded()) Toast.makeText(requireContext(), R.string.crash_report_unavailable, Toast.LENGTH_SHORT).show(); }
		void share() {
			if (sharing || !usable()) return;
			sharing = true;
			String id = requireArguments().getString("id");
			android.content.Context context = requireContext().getApplicationContext();
			CrashReports.execute(() -> {
				try {
					java.io.File exported = CrashReports.export(context, id);
					ConcurrentUtils.HANDLER.post(() -> {
						sharing = false;
						if (!usable()) return;
						try {
							Uri uri = FileProvider.crashReportUri(exported.getName());
							Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
									.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_STREAM, uri);
							intent.setClipData(ClipData.newUri(requireContext().getContentResolver(), "Slooop crash report", uri));
							startActivity(Intent.createChooser(intent, null));
							dismiss();
						} catch (IOException | RuntimeException e) { unavailable(); }
					});
				} catch (IOException | RuntimeException e) { ConcurrentUtils.HANDLER.post(() -> { sharing = false; unavailable(); }); }
			});
		}
	}
	public static final class Offer extends ProtectedDialog {
		@Override @NonNull public Dialog onCreateDialog(Bundle state) {
			return MotionDialogBuilder.standard(requireContext()).setTitle(R.string.crash_report_offer_title)
					.setMessage(R.string.crash_report_offer_message)
					.setPositiveButton(R.string.crash_report_view, null).setNeutralButton(R.string.crash_report_send, null)
					.setNegativeButton(R.string.crash_report_later, null).create();
		}
		@Override public void onStart() {
			super.onStart(); AlertDialog dialog = (AlertDialog) requireDialog();
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
				if (!usable()) return;
				FragmentActivity host = requireActivity(); String id = requireArguments().getString("id");
				dismissNow(); view(host, id);
			});
			dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> share());
			dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> { if (usable()) dismiss(); });
		}
	}
	public static final class Viewer extends ProtectedDialog {
		private boolean loading;
		@Override @NonNull public Dialog onCreateDialog(Bundle state) {
			return MotionDialogBuilder.standard(requireContext()).setTitle(R.string.crash_reports_title)
					.setMessage(R.string.crash_report_loading).setPositiveButton(R.string.crash_report_send, null)
					.setNeutralButton(R.string.delete, null).setNegativeButton(android.R.string.ok, null).create();
		}
		@Override public void onStart() {
			super.onStart(); AlertDialog dialog = (AlertDialog) requireDialog();
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
			dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> share());
			dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
				if (!usable()) return;
				new com.mishiranu.dashchan.widget.MessageDialog.Builder(requireContext())
						.setTitle(R.string.delete).setMessage(R.string.crash_report_delete_confirm)
						.setPositiveButton(R.string.delete, (d, which) -> {
							if (!usable()) return;
							String id = requireArguments().getString("id");
							CrashReports.execute(() -> {
								try { CrashReports.delete(id); ConcurrentUtils.HANDLER.post(() -> { if (isAdded()) dismissAllowingStateLoss(); }); }
								catch (IOException | RuntimeException e) { ConcurrentUtils.HANDLER.post(this::unavailable); }
							});
						}).setNegativeButton(android.R.string.cancel, null).show();
			});
		}
		@Override public void onResume() { super.onResume(); load(); }
		private void load() {
			if (loading || !usable()) return; loading = true;
			String id = requireArguments().getString("id");
			CrashReports.execute(() -> {
				String text;
				try {
					text = CrashReports.read(id);
					try { CrashReports.markOffered(id); } catch (IOException ignored) { /* Reading does not require a successful marker write. */ }
				}
				catch (IOException | RuntimeException e) { text = null; }
				String report = text;
				ConcurrentUtils.HANDLER.post(() -> {
					loading = false;
					if (!usable() || getDialog() == null) return;
					AlertDialog dialog = (AlertDialog) requireDialog();
					dialog.setMessage(report != null ? report : getString(R.string.crash_report_unavailable));
					TextView message = dialog.findViewById(android.R.id.message);
					if (message != null) message.setTextIsSelectable(true);
					dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(report != null);
					// A damaged/expired report must still be removable.
					dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
				});
			});
		}
	}
	public static final class Test extends ProtectedDialog {
		@Override @NonNull public Dialog onCreateDialog(Bundle state) {
			return MotionDialogBuilder.standard(requireContext(), true).setTitle(R.string.crash_test_title)
					.setMessage(R.string.crash_test_confirm).setPositiveButton(R.string.crash_test_title, null)
					.setNegativeButton(android.R.string.cancel, null).create();
		}
		@Override public void onStart() {
			super.onStart(); AlertDialog dialog = (AlertDialog) requireDialog();
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
				if (!usable()) return;
				FragmentActivity host = requireActivity(); long profile = requireArguments().getLong("profile", -1);
				dismissNow();
				ConcurrentUtils.HANDLER.post(() -> {
					if (host.isFinishing() || host.isDestroyed() || SecurityStore.locked(host)
							|| !host.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) return;
					try { if (ProfileGeneration.current(host) != profile) return; } catch (IOException e) { return; }
					CrashReports.crashForUserTest();
				});
			});
		}
	}
}
