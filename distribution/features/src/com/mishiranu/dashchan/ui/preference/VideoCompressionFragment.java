package com.mishiranu.dashchan.ui.preference;

import android.Manifest;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.media.compression.CompressionSettings;
import com.mishiranu.dashchan.media.compression.HardwareCodecProbe;
import com.mishiranu.dashchan.media.compression.HardwareVideoCodecs;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.preference.core.PreferenceFragment;
import com.mishiranu.dashchan.util.SharedPreferences;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.MotionDialogBuilder;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class VideoCompressionFragment extends PreferenceFragment {
	private ProbeModel probe;
	private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
			new ActivityResultContracts.RequestPermission(), granted -> {});
	@Override protected SharedPreferences getPreferences() { return Preferences.PREFERENCES; }
	@Override public void onViewCreated(@NonNull View view, Bundle state) {
		super.onViewCreated(view, state);
		probe = new ViewModelProvider(this).get(ProbeModel.class);
		probe.state.observe(getViewLifecycleOwner(), ignored -> refresh()); refresh();
	}
	private void refresh() {
		if (getView() == null) return;
		removeAllPreferences();
		addCheck(true, CompressionSettings.ENABLED, false, R.string.compression_enabled, R.string.compression_summary)
				.setOnAfterChangeListener(p -> {
					if (p.getValue() && Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
				});
		addButton(R.string.compression_engine, R.string.compression_engine_summary).setOnClickListener(p -> {
			ArrayAdapter<CharSequence> choices = new ArrayAdapter<CharSequence>(requireContext(),
					android.R.layout.simple_list_item_single_choice, new CharSequence[]{"Media3", getString(R.string.compression_direct_unavailable)}) {
				@Override public boolean areAllItemsEnabled() { return false; }
				@Override public boolean isEnabled(int position) { return position == 0; }
				@Override public View getView(int position, View convertView, ViewGroup parent) {
					View row = super.getView(position, convertView, parent); row.setEnabled(isEnabled(position)); return row;
				}
			};
			MotionDialogBuilder.standard(requireContext()).setTitle(R.string.compression_engine)
					.setSingleChoiceItems(choices, 0, (dialog, which) -> { if (which == 0) dialog.dismiss(); })
					.setNegativeButton(android.R.string.cancel, null).show();
		});
		addList(CompressionSettings.CODEC, Arrays.asList("auto", "avc", "hevc"), "auto", R.string.compression_codec,
				Arrays.asList(getString(R.string.compression_codec_auto), "H.264 / AVC", "H.265 / HEVC"));
		addCheck(true, CompressionSettings.ALWAYS, false, R.string.compression_always, R.string.compression_always_summary);
		addHeader(R.string.compression_limits);
		addLimit(CompressionSettings.TOTAL_LIMIT, R.string.compression_total_limit);
		addLimit(CompressionSettings.FILE_LIMIT, R.string.compression_file_limit);
		com.mishiranu.dashchan.ui.preference.core.Preference<Void> test = addButton(R.string.compression_system_test, R.string.compression_test_summary);
		test.setEnabled(!Boolean.TRUE.equals(probe.state.getValue()) && !com.mishiranu.dashchan.media.compression.VideoCompressionService.isBusy());
		test.setOnClickListener(p -> probe.start());
		if (Boolean.TRUE.equals(probe.state.getValue())) addButton(R.string.compression_testing, 0).setEnabled(false);
		else if (probe.reports != null) {
			com.mishiranu.dashchan.ui.preference.core.Preference<Void> result = addButton(R.string.compression_test_result, R.string.compression_test_scope);
			result.setOnClickListener(p -> {
				StringBuilder text = new StringBuilder();
				for (HardwareCodecProbe.Report r : probe.reports) {
					text.append(r.mime.equals(HardwareVideoCodecs.HEVC) ? "H.265 / HEVC" : "H.264 / AVC").append('\n')
							.append(getString(R.string.compression_encoding)).append(": ").append(describe(r.encoderDeclared, r.encodingTested, r.encoded)).append('\n')
							.append(getString(R.string.compression_decoding)).append(": ").append(describe(r.decoderDeclared, r.decodingTested, r.decoded)).append("\n\n");
				}
				text.append(getString(R.string.compression_test_scope));
				MotionDialogBuilder.standard(requireContext()).setTitle(R.string.compression_test_result).setMessage(text)
						.setPositiveButton(android.R.string.ok, null).show();
			});
			if (probe.showResult) getView().post(() -> {
				if (getView() != null && isResumed() && probe.showResult) { probe.showResult = false; result.performClick(); }
			});
		}
	}
	private String describe(boolean declared, boolean tested, boolean passed) {
		return getString(!declared ? R.string.compression_not_declared : passed ? R.string.compression_probe_passed
				: tested ? R.string.compression_probe_failed : R.string.compression_probe_not_tested);
	}
	private void addLimit(String key, int title) {
		addEdit(key, "", title, p -> p.getValue().isEmpty() ? getString(R.string.compression_limit_auto)
				: getString(R.string.compression_limit_mb, p.getValue()), getString(R.string.compression_limit_auto),
				InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL).setOnBeforeChangeListener((p, value) -> {
			if (value.trim().isEmpty() || CompressionSettings.bytes(value) > 0) return true;
			ClickableToast.show(R.string.compression_invalid_limit); return false;
		});
	}
	@Override public void onViewStateRestored(Bundle state) {
		super.onViewStateRestored(state);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.compression_title), null);
	}
	public static final class ProbeModel extends ViewModel {
		final MutableLiveData<Boolean> state = new MutableLiveData<>(false);
		HardwareCodecProbe.Report[] reports;
		boolean showResult;
		private final ExecutorService executor = Executors.newSingleThreadExecutor();
		private Future<?> task;
		void start() {
			if (Boolean.TRUE.equals(state.getValue())) return;
			state.setValue(true);
			task = executor.submit(() -> {
				HardwareCodecProbe.Report avc = HardwareCodecProbe.run(HardwareVideoCodecs.AVC);
				HardwareCodecProbe.Report hevc = HardwareCodecProbe.run(HardwareVideoCodecs.HEVC);
				new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
					if (!executor.isShutdown()) { reports = new HardwareCodecProbe.Report[]{avc, hevc}; showResult = true; state.setValue(false); }
				});
			});
		}
		@Override protected void onCleared() { if (task != null) task.cancel(true); executor.shutdownNow(); }
	}
}
