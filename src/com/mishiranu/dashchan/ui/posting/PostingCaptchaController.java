package com.mishiranu.dashchan.ui.posting;

import android.graphics.Bitmap;
import android.os.SystemClock;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.content.ChanPerformer;
import chan.util.CommonUtils;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.ReadCaptchaTask;
import com.mishiranu.dashchan.content.async.TaskViewModel;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import com.mishiranu.dashchan.util.ConcurrentUtils;
import java.util.List;
import java.util.function.LongSupplier;

/** Captcha state owner. The form stays in the Fragment; Host exists only for its view lifetime. */
public final class PostingCaptchaController implements ReadCaptchaTask.Callback {
	interface Host {
		void updateSendButtonState();
		void showLoading();
		void showCaptcha(ReadCaptchaTask.CaptchaState state, ChanConfiguration.Captcha.Input input,
				Bitmap image, boolean large, boolean blackAndWhite);
		void setInput(String text);
		void showError(ErrorItem errorItem);
		void updatePostingConfigurationIfNeeded();
	}

	/** A detached send snapshot: its data is copied, never the controller's live payload. */
	static final class CaptchaSnapshot {
		final String type;
		final ReadCaptchaTask.CaptchaState state;
		final ChanPerformer.CaptchaData data;
		final ChanConfiguration.Captcha.Input input;
		final ChanConfiguration.Captcha.Validity validity;
		final boolean needLoad;
		CaptchaSnapshot(String type, ReadCaptchaTask.CaptchaState state, ChanPerformer.CaptchaData data,
				ChanConfiguration.Captcha.Input input, ChanConfiguration.Captcha.Validity validity) {
			this.type = type; this.state = state; this.data = data; this.input = input; this.validity = validity;
			needLoad = state == ReadCaptchaTask.CaptchaState.MAY_LOAD || state == ReadCaptchaTask.CaptchaState.MAY_LOAD_SOLVING;
		}
	}

	private Host host;
	private final LongSupplier clock;
	private String chanName, boardName, threadNumber;
	private String captchaType;
	private ReadCaptchaTask.CaptchaState captchaState;
	private ChanPerformer.CaptchaData captchaData;
	private String loadedCaptchaType;
	private ChanConfiguration.Captcha.Input loadedCaptchaInput;
	private ChanConfiguration.Captcha.Validity loadedCaptchaValidity;
	private Bitmap captchaImage;
	private boolean captchaLarge;
	private boolean captchaBlackAndWhite;
	private long captchaLoadTime;

	PostingCaptchaController() { this(SystemClock::elapsedRealtime); }
	PostingCaptchaController(LongSupplier clock) { this.clock = clock; }

	void configure(String chanName, String boardName, String threadNumber, String captchaType) {
		this.chanName = chanName; this.boardName = boardName; this.threadNumber = threadNumber;
		this.captchaType = captchaType;
	}
	void attachView(Host host) { this.host = host; }
	void detachView() { host = null; }
	boolean canSend() { return captchaState != null && captchaState != ReadCaptchaTask.CaptchaState.NEED_LOAD; }

	CaptchaSnapshot snapshot(String userInput) {
		ChanPerformer.CaptchaData data = captchaData != null ? captchaData.copy() : null;
		if (data != null) data.put(ChanPerformer.CaptchaData.INPUT, userInput);
		return new CaptchaSnapshot(loadedCaptchaType != null ? loadedCaptchaType : captchaType,
				captchaState, data, loadedCaptchaInput, loadedCaptchaValidity);
	}

	DraftsStorage.CaptchaDraft createDraft(String input) {
		return new DraftsStorage.CaptchaDraft(captchaType, captchaState, captchaData, loadedCaptchaType,
				loadedCaptchaInput, loadedCaptchaValidity, input, captchaImage, captchaLarge,
				captchaBlackAndWhite, captchaLoadTime, boardName, threadNumber);
	}

	boolean restoreDraft(DraftsStorage.CaptchaDraft draft, boolean savedState, ChanConfiguration.Captcha captcha) {
		if (draft == null || host == null) return false;
		if (savedState) {
			if (draft.captchaState == null) return false;
			captchaLoadTime = draft.loadTime;
			showCaptcha(draft.captchaState, draft.captchaData, draft.loadedCaptchaType,
					draft.loadedInput, draft.loadedValidity, draft.image, draft.large, draft.blackAndWhite);
			host.setInput(draft.text);
			return true;
		}
		if (draft.loadedCaptchaType != null) return false;
		captchaLoadTime = draft.loadTime;
		if (!canRestoreStoredDraft(draft, captcha.validity)) return false;
		if (draft.captchaState == ReadCaptchaTask.CaptchaState.CAPTCHA && draft.image != null) {
			showCaptcha(ReadCaptchaTask.CaptchaState.CAPTCHA, draft.captchaData, null,
					draft.loadedInput, draft.loadedValidity, draft.image, draft.large, draft.blackAndWhite);
			host.setInput(draft.text);
			return true;
		} else if (draft.captchaState == ReadCaptchaTask.CaptchaState.SKIP || draft.captchaState == ReadCaptchaTask.CaptchaState.PASS) {
			showCaptcha(draft.captchaState, draft.captchaData, null, null, draft.loadedValidity, null, false, false);
			return true;
		}
		return false;
	}

	boolean canRestoreStoredDraft(DraftsStorage.CaptchaDraft draft, ChanConfiguration.Captcha.Validity validity) {
		if (draft.loadedCaptchaType != null || !CommonUtils.equals(captchaType, draft.captchaType)) return false;
		if (validity == null) validity = ChanConfiguration.Captcha.Validity.SHORT_LIFETIME;
		if (draft.loadedValidity != null && (draft.captchaState != ReadCaptchaTask.CaptchaState.CAPTCHA
				|| validity.compareTo(draft.loadedValidity) >= 0)) validity = draft.loadedValidity;
		switch (validity) {
			case SHORT_LIFETIME: return false;
			case IN_THREAD: return CommonUtils.equals(boardName, draft.boardName) && CommonUtils.equals(threadNumber, draft.threadNumber);
			case IN_BOARD_SEPARATELY: return CommonUtils.equals(boardName, draft.boardName) && ((threadNumber == null) == (draft.threadNumber == null));
			case IN_BOARD: return CommonUtils.equals(boardName, draft.boardName);
			case LONG_LIFETIME: return true;
			default: return false;
		}
	}

	void bindState(Fragment owner) {
		CaptchaViewModel viewModel = new ViewModelProvider(owner).get(CaptchaViewModel.class);
		viewModel.observe(owner.getViewLifecycleOwner(), this);
	}

	void refreshCaptcha(Fragment owner, boolean forceCaptcha, boolean mayShowLoadButton, boolean restart) {
		if (host == null) return;
		boolean allowSolveAutomatically = !forceCaptcha || captchaState != ReadCaptchaTask.CaptchaState.MAY_LOAD_SOLVING;
		captchaState = null;
		loadedCaptchaType = null;
		captchaLoadTime = 0L;
		host.updateSendButtonState();
		host.showLoading();
		CaptchaViewModel viewModel = new ViewModelProvider(owner).get(CaptchaViewModel.class);
		if (restart || !viewModel.hasTaskOrValue()) {
			Chan chan = Chan.get(chanName);
			List<String> captchaPass = forceCaptcha ? null : Preferences.getCaptchaPass(chan);
			ReadCaptchaTask task = new ReadCaptchaTask(viewModel.callback, null, captchaType, null, captchaPass,
					mayShowLoadButton, allowSolveAutomatically, chan, boardName, threadNumber);
			task.execute(ConcurrentUtils.PARALLEL_EXECUTOR);
			viewModel.attach(task);
		}
	}

	public static class CaptchaViewModel extends TaskViewModel.Proxy<ReadCaptchaTask, ReadCaptchaTask.Callback> {}

	@Override
	public void onReadCaptchaSuccess(ReadCaptchaTask.Result result) {
		if (host == null) return;
		captchaLoadTime = clock.getAsLong();
		showCaptcha(result.captchaState, result.captchaData, result.captchaType, result.input, result.validity,
				result.image, result.large, result.blackAndWhite);
		host.updatePostingConfigurationIfNeeded();
	}
	@Override
	public void onReadCaptchaError(ErrorItem errorItem) {
		if (host == null) return;
		host.showError(errorItem);
		host.updatePostingConfigurationIfNeeded();
	}

	private void showCaptcha(ReadCaptchaTask.CaptchaState state, ChanPerformer.CaptchaData data,
			String type, ChanConfiguration.Captcha.Input input, ChanConfiguration.Captcha.Validity validity,
			Bitmap image, boolean large, boolean blackAndWhite) {
		captchaState = state;
		if (captchaImage != null && captchaImage != image) captchaImage.recycle();
		captchaData = data; captchaImage = image; captchaLarge = large; captchaBlackAndWhite = blackAndWhite;
		loadedCaptchaType = type;
		if (type != null) {
			ChanConfiguration.Captcha captcha = Chan.get(chanName).configuration.safe().obtainCaptcha(type);
			if (input == null) input = captcha.input;
			if (validity == null) validity = captcha.validity;
		}
		loadedCaptchaInput = input; loadedCaptchaValidity = validity;
		host.showCaptcha(state, input, image, large, blackAndWhite);
		host.updateSendButtonState();
	}
}
