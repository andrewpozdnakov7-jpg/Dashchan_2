package com.mishiranu.dashchan.ui.posting;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import chan.content.Chan;
import chan.content.ChanConfiguration;
import chan.content.ChanMarkup;
import chan.text.CommentEditor;
import chan.util.CommonUtils;
import chan.util.StringUtils;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.chan.dvach.DvachChanConfiguration;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.content.async.ReadCaptchaTask;
import com.mishiranu.dashchan.content.async.SendPostTask;
import com.mishiranu.dashchan.content.model.ErrorItem;
import com.mishiranu.dashchan.content.model.PostNumber;
import com.mishiranu.dashchan.content.service.PostingService;
import com.mishiranu.dashchan.content.storage.DraftsStorage;
import com.mishiranu.dashchan.ui.CaptchaForm;
import com.mishiranu.dashchan.ui.ContentFragment;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.posting.dialog.SendPostFailDetailsDialog;
import com.mishiranu.dashchan.ui.posting.text.CommentEditWatcher;
import com.mishiranu.dashchan.ui.posting.text.MarkupButtonProvider;
import com.mishiranu.dashchan.ui.posting.text.NameEditWatcher;
import com.mishiranu.dashchan.ui.posting.text.QuoteEditWatcher;
import com.mishiranu.dashchan.util.AndroidUtils;
import com.mishiranu.dashchan.util.GraphicsUtils;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.ClickableToast;
import com.mishiranu.dashchan.widget.DropdownView;
import com.mishiranu.dashchan.widget.ExpandedLayout;
import com.mishiranu.dashchan.widget.ProgressDialog;
import com.mishiranu.dashchan.widget.ThemeEngine;
import com.mishiranu.dashchan.widget.UriPasteEditText;
import com.mishiranu.dashchan.widget.ViewFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class PostingFragment extends ContentFragment implements FragmentHandler.Callback, CaptchaForm.Callback,
		PostingDialogCallback, UriPasteEditText.Callback {
	private static final String EXTRA_CHAN_NAME = "chanName";
	private static final String EXTRA_BOARD_NAME = "boardName";
	private static final String EXTRA_THREAD_NUMBER = "threadNumber";
	private static final String EXTRA_REPLY_DATA_LIST = "replyDataList";
	private static final String CHAN_NAME_DVACH = "dvach";
	private static final String CHAN_NAME_APACHAN = "apachan";
	private static final String COMMAND_MONKEY = "@monkey";
	private static final String COMMAND_ART_MONKEY = "@artmonkey";
	private static final int SERVER_COMMAND_BUTTON_MONKEY = 1;
	private static final int SERVER_COMMAND_BUTTON_ART_MONKEY = 1 << 1;
	private static final int SERVER_COMMAND_BUTTON_APACHAN_VIDEO = 1 << 2;
	private static final int SERVER_COMMAND_BUTTON_WIDTH_DP = 40;
	private static final int COMMENT_MIN_LINES = 4;
	private static final int COMMENT_MAX_LINES = 9;

	private static final String EXTRA_CAPTCHA_DRAFT = "captchaDraft";

	// Launchers belong to the Fragment, not an attachment controller.
	// Register in a fixed order for every instance, including restoration while a picker is open.
	private final ActivityResultLauncher<Intent> attachmentPicker = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(), result -> handlePostingActivityResult(
					C.REQUEST_CODE_ATTACH, result.getResultCode(), result.getData()));
	private final ActivityResultLauncher<Intent> imageEditor = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(), result -> handlePostingActivityResult(
					C.REQUEST_CODE_IMAGE_EDITOR, result.getResultCode(), result.getData()));

	public PostingFragment() {}

	public PostingFragment(String chanName, String boardName, String threadNumber,
			List<Replyable.ReplyData> replyDataList) {
		Bundle args = new Bundle();
		args.putString(EXTRA_CHAN_NAME, chanName);
		args.putString(EXTRA_BOARD_NAME, boardName);
		args.putString(EXTRA_THREAD_NUMBER, threadNumber);
		args.putParcelableArrayList(EXTRA_REPLY_DATA_LIST, new ArrayList<>(replyDataList));
		setArguments(args);
	}

	private String getChanName() {
		return requireArguments().getString(EXTRA_CHAN_NAME);
	}

	private String getBoardName() {
		return requireArguments().getString(EXTRA_BOARD_NAME);
	}

	private String getThreadNumber() {
		return requireArguments().getString(EXTRA_THREAD_NUMBER);
	}

	public boolean check(String chanName, String boardName, String threadNumber) {
		return CommonUtils.equals(getChanName(), chanName) &&
				CommonUtils.equals(getBoardName(), boardName) &&
				CommonUtils.equals(getThreadNumber(), threadNumber);
	}

	// Screen-level posting permission; send results and draft guards live in their controllers.
	private boolean allowPosting;

	private CommentEditor commentEditor;

	private ChanConfiguration.Posting postingConfiguration;
	private List<Pair<String, String>> userIconItems;

	// One state owner per subsystem. Hosts are attached only for the current view lifetime.
	private final PostingAttachmentsController attachmentController = new PostingAttachmentsController();
	private final PostingCaptchaController captchaController = new PostingCaptchaController();
	private final PostingDraftController draftController = new PostingDraftController();
	private final PostingSendCoordinator sendCoordinator = new PostingSendCoordinator(draftController);

	// View-owned state: initialized in onViewCreated and released in onDestroyView.
	private PostingFormDiagnostics.Observer formDiagnostics;

	private ScrollView scrollView;
	private UriPasteEditText commentView;
	private CheckBox sageCheckBox;
	private CheckBox spoilerCheckBox;
	private CheckBox originalPosterCheckBox;
	private View checkBoxParent;
	private EditText nameView;
	private EditText emailView;
	private EditText passwordView;
	private EditText subjectView;
	private DropdownView iconView;
	private ViewGroup personalDataBlock;
	private ViewGroup textFormatView;
	private CommentEditWatcher commentEditWatcher;
	private CaptchaForm captchaForm;
	private Button sendButton;
	private ProgressDialog progressDialog;

	private boolean sendButtonEnabled = true;

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		ExpandedLayout rootView = new ExpandedLayout(container.getContext(), true);
		rootView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		inflater.inflate(R.layout.activity_posting, rootView);
		return rootView;
	}

	@Override
	// Keep the native send button and its custom measurement under ThemeEngine.
	@android.annotation.SuppressLint("AppCompatCustomView")
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		Chan chan = Chan.get(getChanName());
		postingConfiguration = chan.configuration.safe().obtainPosting(getBoardName(), getThreadNumber() == null);
		if (postingConfiguration != null) {
			allowPosting = chan.configuration.safe().obtainBoard(getBoardName()).allowPosting;
		} else {
			postingConfiguration = new ChanConfiguration.Posting();
			allowPosting = false;
		}

		String captchaType = chan.configuration.getCaptchaType();
		captchaController.configure(getChanName(), getBoardName(), getThreadNumber(), captchaType);
		if (allowPosting) {
			commentEditor = chan.markup.safe().obtainCommentEditor(getBoardName());
		}
		float density = ResourceUtils.obtainDensity(view);
		int screenWidthDp = ViewUtils.getWindowContentWidthDp(requireContext());
		boolean hugeCaptcha = Preferences.isHugeCaptcha();
		boolean longLayout = screenWidthDp >= 480;
		boolean longFooter = longLayout && !hugeCaptcha;

		scrollView = view.findViewById(R.id.scroll_view);
		scrollView.getViewTreeObserver().addOnPreDrawListener(showCommentAfterLayout);
		ViewGroup postingLayout = view.findViewById(R.id.posting_layout);
		LinearLayout commentParent = view.findViewById(R.id.comment_parent);
		commentView = view.findViewById(R.id.comment);
		sageCheckBox = view.findViewById(R.id.sage_checkbox);
		spoilerCheckBox = view.findViewById(R.id.spoiler_checkbox);
		originalPosterCheckBox = view.findViewById(R.id.original_poster_checkbox);
		checkBoxParent = view.findViewById(R.id.checkbox_parent);
		nameView = view.findViewById(R.id.name);
		emailView = view.findViewById(R.id.email);
		passwordView = view.findViewById(R.id.password);
		subjectView = view.findViewById(R.id.subject);
		iconView = view.findViewById(R.id.icon);
		personalDataBlock = view.findViewById(R.id.personal_data_block);
		LinearLayout attachmentContainer = view.findViewById(R.id.attachment_container);
		FrameLayout footerContainer = view.findViewById(R.id.footer_container);
		int[] oldScrollViewSize = {-1, -1};
		scrollView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
			if (scrollView != null) {
				int scrollViewWidth = scrollView.getWidth();
				int scrollViewHeight = scrollView.getHeight();
				if (scrollViewWidth != oldScrollViewSize[0] || scrollViewHeight != oldScrollViewSize[1]) {
					oldScrollViewSize[0] = scrollViewWidth;
					oldScrollViewSize[1] = scrollViewHeight;
					resizeComment(false);
					scheduleCommentCursorVisibility();
				}
			}
		});
		postingLayout.setPadding((int) (8f * density), 0, (int) (8f * density), 0);
		addHeader(personalDataBlock, 0, R.string.personal_data);
		addHeader(postingLayout, postingLayout.indexOfChild(subjectView), R.string.message_data);
		TextView tripcodeWarning = view.findViewById(R.id.personal_tripcode_warning);
		TextView remainingCharacters = view.findViewById(R.id.remaining_characters);
		ViewUtils.setTextSizeScaled(tripcodeWarning, 12);
		tripcodeWarning.setPadding((int) (4f * density), 0, (int) (4f * density), (int) (4f * density));
		ViewUtils.setTextSizeScaled(remainingCharacters, 12);
		ViewUtils.setNewMargin(remainingCharacters, 0, (int) (-2f * density), 0, 0);
		nameView.addTextChangedListener(new NameEditWatcher(postingConfiguration.allowName &&
				!postingConfiguration.allowTripcode, nameView, tripcodeWarning, () -> resizeComment(true)));
		ViewUtils.applyMonospaceTypeface(passwordView);
		commentEditWatcher = new CommentEditWatcher(postingConfiguration, commentView, remainingCharacters,
				() -> resizeComment(true), () -> draftController.storePostDraft(capturePostDraft()));
		commentView.setOnFocusChangeListener((v, hasFocus) -> {
			updateFocusButtons(hasFocus);
			if (hasFocus) {
				scheduleCommentCursorVisibility();
			}
		});
		commentView.setMinLines(COMMENT_MIN_LINES);
		commentView.setMaxLines(COMMENT_MAX_LINES);
		commentView.addOnLayoutChangeListener((v, left, top, right, bottom,
				oldLeft, oldTop, oldRight, oldBottom) -> {
			if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
				scheduleCommentCursorVisibility();
			}
		});
		commentView.setOnTouchListener(new View.OnTouchListener() {
			private float previousY;

			@Override
			public boolean onTouch(View view, MotionEvent event) {
				switch (event.getActionMasked()) {
					case MotionEvent.ACTION_DOWN: {
						revealCommentAfterLayout = false;
						previousY = event.getY();
						scrollView.requestDisallowInterceptTouchEvent(true);
						break;
					}
					case MotionEvent.ACTION_MOVE: {
						float y = event.getY();
						int direction = y < previousY ? 1 : y > previousY ? -1 : 0;
						previousY = y;
						boolean scrollComment = direction != 0 && commentView.canScrollVertically(direction);
						scrollView.requestDisallowInterceptTouchEvent(scrollComment);
						break;
					}
					case MotionEvent.ACTION_UP:
					case MotionEvent.ACTION_CANCEL: {
						scrollView.requestDisallowInterceptTouchEvent(false);
						break;
					}
				}
				return false;
			}
		});
		commentView.addTextChangedListener(commentEditWatcher);
		commentView.addTextChangedListener(new QuoteEditWatcher(requireContext()));
		commentView.setCallback(this, PostingAttachmentsController.buildMimeTypeList(postingConfiguration.attachmentMimeTypes));
		boolean addPaddingToRoot = false;
		boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
		ViewGroup extra = landscape ? ((FragmentHandler) requireActivity()).getToolbarView()
				: ((FragmentHandler) requireActivity()).getToolbarExtra();
		LinearLayout textFormatView = new LinearLayout(extra.getContext());
		textFormatView.setOrientation(LinearLayout.HORIZONTAL);
		this.textFormatView = textFormatView;
		if (landscape) {
			boolean rtl = textFormatView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
			textFormatView.setPadding(rtl ? 0 : (int) (8f * density), 0, rtl ? (int) (8f * density) : 0, 0);
		} else {
			textFormatView.setPadding((int) (8f * density), 0, (int) (8f * density), (int) (4f * density));
			addPaddingToRoot = true;
		}
		extra.addView(textFormatView, ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		commentParent.removeView(commentView);
		postingLayout.addView(commentView, postingLayout.indexOfChild(commentParent));
		postingLayout.removeView(commentParent);
		ViewUtils.setNewMargin(checkBoxParent, 0, (int) (4f * density), 0, 0);
		updatePostingConfiguration(true, false, false);
		new MarkupButtonsBuilder(addPaddingToRoot, ViewUtils.getWindowContentSize(requireContext()).x);

		// The complete captcha and send block lives in the fixed bottom panel. Its measured
		// height reduces the scroll viewport, so loading a larger captcha also resizes the editor.
		int resId = longFooter ? R.layout.activity_posting_footer_long : R.layout.activity_posting_footer_common;
		getLayoutInflater().inflate(resId, footerContainer);
		LinearLayout captchaInputParentView = footerContainer.findViewById(R.id.captcha_input_parent);
		EditText captchaInputView = footerContainer.findViewById(R.id.captcha_input);
		captchaInputParentView.setPadding(0, longFooter ? (int) (8f * density) : 0, 0, (int) (8f * density));
		ViewUtils.setNewMarginRelative(captchaInputView, null, null, (int) (4f * density), null);
		ChanConfiguration.Captcha captcha = chan.configuration.safe().obtainCaptcha(captchaType);
		captchaForm = new CaptchaForm(this, true, !longFooter,
				footerContainer, captchaInputParentView, captchaInputView, captcha);
		captchaController.attachView(createCaptchaHost());
		float maxTranslationZ = (int) (2f * density);
		sendButton = new Button(captchaInputParentView.getContext(), null, 0,
				android.R.style.Widget_Material_Button_Colored) {
			@Override
			public void setTranslationZ(float translationZ) {
				super.setTranslationZ(Math.min(translationZ, maxTranslationZ));
			}
		};
		ThemeEngine.applyStyle(sendButton);
		Rect rect = new Rect();
		// Limit elevation height since the shadow looks ugly when the view is at the bottom.
		sendButton.setOutlineProvider(new ViewOutlineProvider() {
			@Override
			public void getOutline(View view, Outline outline) {
				view.getBackground().getOutline(outline);
				if (ViewUtils.getOutlineRect(outline, rect)) {
					float radius = ViewUtils.getOutlineRadius(outline);
					rect.bottom -= (int) (2f * density);
					outline.setRoundRect(rect, radius);
				}
			}
		});
		ThemeEngine.Theme theme = ThemeEngine.getTheme(sendButton.getContext());
		int colorControlDisabled = GraphicsUtils.applyAlpha(theme.controlNormal21, theme.disabledAlpha21);
		int[][] states = {{-android.R.attr.state_enabled}, {}};
		int[] colors = {colorControlDisabled, theme.accent};
		sendButton.setBackgroundTintList(new ColorStateList(states, colors));
		sendButton.setSingleLine(true);
		// setSingleLine breaks capitalization.
		sendButton.setAllCaps(true);
		captchaInputParentView.addView(sendButton, 0, LinearLayout.LayoutParams.WRAP_CONTENT);
		sendButton.setText(R.string.send);
		sendButton.setOnClickListener(v -> submitPostingForm());
		if (longFooter) {
			((LinearLayout.LayoutParams) sendButton.getLayoutParams()).weight = 2f;
			boolean[] lastAddWeight = {true};
			captchaInputParentView.addOnLayoutChangeListener((v, left, top, right, bottom,
					oldLeft, oldTop, oldRight, oldBottom) -> {
				boolean addWeight = captchaInputView.getVisibility() == View.GONE;
				if (addWeight != lastAddWeight[0]) {
					lastAddWeight[0] = addWeight;
					((LinearLayout.LayoutParams) sendButton.getLayoutParams()).weight = addWeight ? 2f : 1f;
					sendButton.requestLayout();
				}
			});
		} else {
			((LinearLayout.LayoutParams) sendButton.getLayoutParams()).weight = 1f;
		}
		attachmentController.attachView(createAttachmentHost(), attachmentContainer, scrollView, sendButton,
				screenWidthDp >= 960 ? 4 : screenWidthDp >= 480 ? 2 : 1);

		StringBuilder builder = new StringBuilder();
		int commentCarriage = 0;

		DraftsStorage.PostDraft postDraft = draftController.restorePostDraft(getChanName(), getBoardName(), getThreadNumber());
		if (postDraft != null) {
			if (!StringUtils.isEmpty(postDraft.comment)) {
				builder.append(postDraft.comment);
				commentCarriage = postDraft.commentCarriage;
			}
			attachmentController.restoreAttachmentDrafts(postDraft.attachmentDrafts);
			nameView.setText(postDraft.name);
			emailView.setText(postDraft.email);
			passwordView.setText(postDraft.password);
			subjectView.setText(postDraft.subject);
			sageCheckBox.setChecked(postDraft.optionSage);
			spoilerCheckBox.setChecked(postDraft.optionSpoiler);
			originalPosterCheckBox.setChecked(postDraft.optionOriginalPoster);
			if (userIconItems != null) {
				int index = 0;
				if (postDraft.userIcon != null) {
					for (int i = 0; i < userIconItems.size(); i++) {
						if (postDraft.userIcon.equals(userIconItems.get(i).first)) {
							index = i + 1;
							break;
						}
					}
				}
				iconView.setSelection(index);
			}
		}

		boolean savedCaptcha = savedInstanceState != null && savedInstanceState.containsKey(EXTRA_CAPTCHA_DRAFT);
		DraftsStorage.CaptchaDraft captchaDraft = savedCaptcha
				? AndroidUtils.getParcelable(savedInstanceState, EXTRA_CAPTCHA_DRAFT, DraftsStorage.CaptchaDraft.class)
				: draftController.getCaptchaDraft(getChanName());
		boolean captchaRestoreSuccess = captchaController.restoreDraft(captchaDraft, savedCaptcha, captcha);

		List<Replyable.ReplyData> replyDataList = savedInstanceState != null ? Collections.emptyList()
				: AndroidUtils.getParcelableArrayList(requireArguments(), EXTRA_REPLY_DATA_LIST,
						Replyable.ReplyData.class);
		if (!replyDataList.isEmpty()) {
			boolean onlyLinks = true;
			for (Replyable.ReplyData data : replyDataList) {
				if (!StringUtils.isEmpty(data.comment)) {
					onlyLinks = false;
					break;
				}
			}
			for (int i = 0; i < replyDataList.size(); i++) {
				boolean lastLink = i == replyDataList.size() - 1;
				Replyable.ReplyData data = replyDataList.get(i);
				PostNumber postNumber = data.postNumber;
				String comment = data.comment;
				if (postNumber != null) {
					String link = ">>" + postNumber;
					// Check if user replies to the same post
					int index = builder.lastIndexOf(link, commentCarriage);
					if (index < 0 || index < commentCarriage && commentCarriage <= builder.length() &&
							builder.substring(index, commentCarriage).contains("\n>>")) {
						boolean afterSpace = false; // If user wants to add link at the same line
						if (commentCarriage > 0 && commentCarriage <= builder.length()) {
							char charBefore = builder.charAt(commentCarriage - 1);
							if (charBefore != '\n') {
								if (charBefore == ' ' && onlyLinks) {
									afterSpace = true;
								} else {
									// Ensure free line before link
									builder.insert(commentCarriage++, '\n');
								}
							}
						}
						builder.insert(commentCarriage, link);
						commentCarriage += link.length();
						if (afterSpace) {
							if (!lastLink) {
								builder.insert(commentCarriage, ", ");
								commentCarriage += 2;
							}
						} else {
							builder.insert(commentCarriage++, '\n');
							if (commentCarriage < builder.length()) {
								if (builder.charAt(commentCarriage) != '\n') {
									// Ensure free line for typing
									builder.insert(commentCarriage, '\n');
								}
							}
						}
					}
				}
				if (!StringUtils.isEmpty(comment)) {
					if (commentCarriage > 0 && commentCarriage <= builder.length() &&
							builder.charAt(commentCarriage - 1) != '\n') {
						builder.insert(commentCarriage++, '\n');
					}
					// Remove links in the beginning of the post
					comment = comment.replaceAll("(^|\n)(>>\\d+(\n|\\s)?)+", "$1");
					comment = comment.replaceAll("(\n+)", "$1> ");
					builder.insert(commentCarriage, "> ");
					commentCarriage += 2;
					builder.insert(commentCarriage, comment);
					commentCarriage += comment.length();
					builder.insert(commentCarriage++, '\n');
				}
			}
		}

		commentView.setText(builder);
		commentView.setSelection(commentCarriage);
		commentView.requestFocus();
		if (!captchaRestoreSuccess) {
			captchaController.refreshCaptcha(this, false, true, false);
		}
		bindControllers();
		formDiagnostics = new PostingFormDiagnostics.Observer(requireActivity(), view, scrollView,
				commentView, footerContainer);
	}

	@Override
	public void onDestroyView() {
		if (formDiagnostics != null) {
			formDiagnostics.close();
			formDiagnostics = null;
		}
		super.onDestroyView();

		sendCoordinator.unbind();
		saveDraftIfNeeded();
		captchaController.detachView();
		ViewUtils.removeFromParent(textFormatView);
		scrollView.removeCallbacks(resizeComment);
		scrollView.getViewTreeObserver().removeOnPreDrawListener(showCommentAfterLayout);
		revealCommentAfterLayout = false;
		scrollView = null;
		commentView = null;
		sageCheckBox = null;
		spoilerCheckBox = null;
		originalPosterCheckBox = null;
		checkBoxParent = null;
		attachmentController.detachView();
		nameView = null;
		emailView = null;
		passwordView = null;
		subjectView = null;
		iconView = null;
		personalDataBlock = null;
		textFormatView = null;
		commentEditWatcher = null;
		captchaForm = null;
		sendButton = null;
	}

	private void bindControllers() {
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(StringUtils.isEmpty(getThreadNumber())
				? R.string.new_thread : R.string.new_post), null);
		sendCoordinator.bind(createSendHost(), requireActivity(), getChanName(), getBoardName(), getThreadNumber());

		captchaController.bindState(this);

		attachmentController.bindImportState(this);
	}

	// Snapshot raw form values (including hidden fields), unlike the visibility-filtered send payload.
	private DraftsStorage.PostDraft capturePostDraft() {
		ArrayList<DraftsStorage.AttachmentDraft> attachmentDrafts = attachmentController.createAttachmentDrafts();
		String subject = subjectView.getText().toString();
		String comment = commentView.getText().toString();
		int commentCarriage = commentView.getSelectionEnd();
		String name = nameView.getText().toString();
		String email = emailView.getText().toString();
		String password = passwordView.getText().toString();
		boolean optionSage = sageCheckBox.isChecked();
		boolean optionSpoiler = spoilerCheckBox.isChecked();
		boolean optionOriginalPoster = originalPosterCheckBox.isChecked();
		String userIcon = getUserIcon();
		return draftController.obtainPostDraft(getChanName(), getBoardName(), getThreadNumber(), name, email, password,
				subject, comment, commentCarriage, attachmentDrafts,
				optionSage, optionSpoiler, optionOriginalPoster, userIcon);
	}

	private DraftsStorage.CaptchaDraft captureCaptchaDraft() {
		return captchaController.createDraft(captchaForm.getInput());
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);

		DraftsStorage.CaptchaDraft captchaDraft = captureCaptchaDraft();
		outState.putParcelable(EXTRA_CAPTCHA_DRAFT, captchaDraft);
		saveDraftIfNeeded();
	}

	@Override
	public void onResume() {
		super.onResume();

		if (allowPosting) {
			consumeFuturePostText();
		}
		ArrayList<DraftsStorage.AttachmentDraft> futureAttachmentDrafts = draftController.getFutureAttachmentDrafts();
		if (!futureAttachmentDrafts.isEmpty()) {
			attachmentController.addFutureAttachments(futureAttachmentDrafts);
			draftController.consumeFutureAttachmentDrafts();
		}

		sendCoordinator.deliverPendingUiEvents();
		draftController.resetSavedGuard();
		if (!allowPosting || sendCoordinator.isSendSuccess()) {
			((FragmentHandler) requireActivity()).removeFragment();
		}
	}

	public boolean consumeFuturePostText() {
		if (!allowPosting || commentView == null) {
			return false;
		}
		String text = Preferences.consumeFuturePostText();
		if (StringUtils.isEmpty(text)) {
			return false;
		}
		Editable editable = commentView.getText();
		if (editable.length() > 0 && editable.charAt(editable.length() - 1) != '\n') {
			editable.append('\n');
		}
		editable.append(text);
		commentView.setSelection(editable.length());
		commentView.requestFocus();
		draftController.storePostDraft(capturePostDraft());
		return true;
	}

	@Override
	public void onChansChanged(Collection<String> changed, Collection<String> removed) {
		if (changed.contains(getChanName()) || removed.contains(getChanName())) {
			updatePostingConfigurationIfNeeded();
			if (!allowPosting) {
				((FragmentHandler) requireActivity()).removeFragment();
			}
		}
	}

	// Should be called both from onDestroyView (1) and onSaveInstanceState (2).
	// 1: Ensures draft is saved when user leaves posting screen.
	// 2: Ensures draft is saved when activity is recreated.
	private void saveDraftIfNeeded() {
		draftController.saveDraft(sendCoordinator.isSendSuccess(), getChanName(), this::capturePostDraft, this::captureCaptchaDraft);
	}

	@Override
	public void onRefreshCaptcha(boolean forceRefresh) {
		captchaController.refreshCaptcha(this, forceRefresh, false, true);
	}

	@Override
	public void onConfirmCaptcha() {
		submitPostingForm();
	}

	private static void addHeader(ViewGroup layout, int index, int textResId) {
		TextView textView = ViewFactory.makeListTextHeader(layout);
		textView.setText(textResId);
		layout.addView(textView, index);
		float density = ResourceUtils.obtainDensity(textView);
		textView.setPadding((int) (4f * density), 0, (int) (4f * density), 0);
		ViewUtils.setNewMargin(textView, 0, 0, 0, (int) (-8f * density));
	}

	private void updatePostingConfiguration(boolean views, boolean attachmentOptions, boolean attachmentCount) {
		ChanConfiguration.Posting posting = postingConfiguration;
		if (views) {
			userIconItems = posting.userIcons.size() > 0 ? posting.userIcons : null;
			if (userIconItems != null) {
				String lastUserIcon = getUserIcon();
				int lastUserIconIndex = -1;
				ArrayList<String> items = new ArrayList<>();
				items.add(getString(R.string.no_icon));
				for (int i = 0; i < userIconItems.size(); i++) {
					Pair<String, String> iconItem = userIconItems.get(i);
					items.add(iconItem.second);
					if (CommonUtils.equals(lastUserIcon, iconItem.first)) {
						lastUserIconIndex = i;
					}
				}
				iconView.setItems(items);
				iconView.setVisibility(View.VISIBLE);
				iconView.setSelection(lastUserIconIndex + 1);
			} else {
				iconView.setVisibility(View.GONE);
			}
			boolean needPassword = false;
			Chan chan = Chan.get(getChanName());
			ChanConfiguration.Board board = chan.configuration.safe().obtainBoard(getChanName());
			if (board.allowDeleting) {
				ChanConfiguration.Deleting deleting = chan.configuration.safe().obtainDeleting(getChanName());
				needPassword = deleting != null && deleting.password;
			}
			nameView.setVisibility(posting.allowName ? View.VISIBLE : View.GONE);
			emailView.setVisibility(posting.allowEmail ? View.VISIBLE : View.GONE);
			passwordView.setVisibility(needPassword ? View.VISIBLE : View.GONE);
			subjectView.setVisibility(posting.allowSubject ? View.VISIBLE : View.GONE);
			sageCheckBox.setVisibility(posting.optionSage ? View.VISIBLE : View.GONE);
			spoilerCheckBox.setVisibility(posting.optionSpoiler ? View.VISIBLE : View.GONE);
			originalPosterCheckBox.setVisibility(posting.optionOriginalPoster ? View.VISIBLE : View.GONE);
			checkBoxParent.setVisibility(posting.optionSage || posting.optionSpoiler || posting.optionOriginalPoster
					? View.VISIBLE : View.GONE);
			boolean showPersonalDataBlock = !Preferences.isHidePersonalData();
			if (showPersonalDataBlock) {
				showPersonalDataBlock = posting.allowName || posting.allowEmail ||
						needPassword || userIconItems != null;
			}
			personalDataBlock.setVisibility(showPersonalDataBlock ? View.VISIBLE : View.GONE);
			commentEditWatcher.updateConfiguration(postingConfiguration);
		}
		attachmentController.updateConfiguration(posting, attachmentOptions, attachmentCount);
	}

	private boolean compareListOfPairs(List<Pair<String, String>> first, List<Pair<String, String>> second) {
		if (first.size() != second.size()) {
			return false;
		}
		for (int i = 0; i < first.size(); i++) {
			if (!CommonUtils.equals(first.get(i).first, second.get(i).first)
					|| !CommonUtils.equals(first.get(i).second, second.get(i).second)) {
				return false;
			}
		}
		return true;
	}

	private void updatePostingConfigurationIfNeeded() {
		Chan chan = Chan.get(getChanName());
		ChanConfiguration.Posting oldPosting = postingConfiguration;
		ChanConfiguration.Posting newPosting = chan.configuration
				.safe().obtainPosting(getBoardName(), getThreadNumber() == null);
		if (newPosting == null) {
			allowPosting = false;
			newPosting = new ChanConfiguration.Posting();
		} else {
			allowPosting = chan.configuration.safe().obtainBoard(getBoardName()).allowPosting;
		}
		boolean views = oldPosting.allowName != newPosting.allowName || oldPosting.allowEmail != newPosting.allowEmail
				|| oldPosting.allowTripcode != newPosting.allowTripcode
				|| oldPosting.allowSubject != newPosting.allowSubject || oldPosting.optionSage != newPosting.optionSage
				|| oldPosting.optionSpoiler != newPosting.optionSpoiler
				|| oldPosting.optionOriginalPoster != newPosting.optionOriginalPoster
				|| oldPosting.maxCommentLength != newPosting.maxCommentLength
				|| !CommonUtils.equals(oldPosting.maxCommentLengthEncoding, newPosting.maxCommentLengthEncoding)
				|| !compareListOfPairs(oldPosting.userIcons, newPosting.userIcons);
		boolean attachmentOptions = oldPosting.attachmentSpoiler != newPosting.attachmentSpoiler
				|| !compareListOfPairs(oldPosting.attachmentRatings, newPosting.attachmentRatings);
		boolean attachmentCount = oldPosting.attachmentCount != newPosting.attachmentCount;
		if (views || attachmentOptions || attachmentCount) {
			postingConfiguration = newPosting;
			updatePostingConfiguration(views, attachmentOptions, attachmentCount);
			resizeComment(true);
		}
	}

	private String getUserIcon() {
		if (userIconItems != null) {
			int position = iconView.getSelectedItemPosition() - 1;
			if (position >= 0 && position < userIconItems.size()) {
				return userIconItems.get(position).first;
			}
		}
		return null;
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, boolean primary) {
		menu.add(0, R.id.menu_attach, 0, R.string.attach)
				.setIcon(((FragmentHandler) requireActivity()).getActionBarIcon(R.attr.iconActionAttach))
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
	}

	@Override
	public void onPrepareOptionsMenu(Menu menu, boolean primary) {
		menu.findItem(R.id.menu_attach).setVisible(attachmentController.canAttach());
	}

	@Override
	public UriPasteEditText.PasteResult onUrisWithAllowedMimeTypePasted(
			List<UriPasteEditText.UriContent> uriContents) {
		return attachmentController.onUrisWithAllowedMimeTypePasted(uriContents);
	}

	@Override
	public boolean onMenuItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.menu_attach) {
			attachmentController.openPicker();
		}
		return true;
	}

	private void updateFocusButtons(boolean commentFocused) {
		for (int i = 0; i < textFormatView.getChildCount(); i++) {
			textFormatView.getChildAt(i).setClickable(commentFocused);
		}
	}

	private final View.OnClickListener formatButtonClickListener = new View.OnClickListener() {
		@Override
		public void onClick(View v) {
			int what = (int) v.getTag();
			switch (what) {
				case ChanMarkup.TAG_QUOTE: {
					if (commentEditor.getTag(ChanMarkup.TAG_QUOTE, false) != null) {
						commentEditor.formatSelectedText(commentView, what);
					} else {
						formatQuote();
					}
					break;
				}
				default: {
					commentEditor.formatSelectedText(commentView, what);
					break;
				}
			}
			InputMethodManager inputMethodManager = (InputMethodManager) requireContext()
					.getSystemService(Context.INPUT_METHOD_SERVICE);
			if (inputMethodManager != null) {
				inputMethodManager.showSoftInput(commentView, 0);
			}
		}
	};

	private int getServerCommandButtons() {
		if (!allowPosting) {
			return 0;
		}
		if (CHAN_NAME_APACHAN.equals(getChanName())) {
			return SERVER_COMMAND_BUTTON_APACHAN_VIDEO;
		}
		if (!CHAN_NAME_DVACH.equals(getChanName())) return 0;
		Chan chan = Chan.get(getChanName());
		if (!(chan.configuration instanceof DvachChanConfiguration)) {
			return 0;
		}
		DvachChanConfiguration configuration = (DvachChanConfiguration) chan.configuration;
		int buttons = 0;
		if (configuration.isMonkeyButtonEnabled()) {
			buttons |= SERVER_COMMAND_BUTTON_MONKEY;
		}
		if (configuration.isArtMonkeyButtonEnabled()
				&& Preferences.checkHasMultipleValues(Preferences.getCaptchaPass(chan))) {
			buttons |= SERVER_COMMAND_BUTTON_ART_MONKEY;
		}
		return buttons;
	}

	private void insertServerCommand(String command) {
		Editable editable = commentView.getText();
		int length = editable.length();
		int rawSelectionStart = commentView.getSelectionStart();
		int rawSelectionEnd = commentView.getSelectionEnd();
		if (rawSelectionStart < 0) {
			rawSelectionStart = length;
		}
		if (rawSelectionEnd < 0) {
			rawSelectionEnd = rawSelectionStart;
		}
		int selectionStart = Math.min(Math.min(rawSelectionStart, rawSelectionEnd), length);
		int selectionEnd = Math.min(Math.max(rawSelectionStart, rawSelectionEnd), length);

		int lineStart = selectionStart;
		while (lineStart > 0 && editable.charAt(lineStart - 1) != '\n') {
			lineStart--;
		}
		boolean hasTextBeforeCursor = false;
		for (int i = lineStart; i < selectionStart; i++) {
			if (!Character.isWhitespace(editable.charAt(i))) {
				hasTextBeforeCursor = true;
				break;
			}
		}

		SpannableStringBuilder replacement = new SpannableStringBuilder();
		if (hasTextBeforeCursor) {
			replacement.append('\n');
		}
		replacement.append(command).append(' ');
		if (selectionStart != selectionEnd) {
			replacement.append(editable, selectionStart, selectionEnd);
		}
		editable.replace(selectionStart, selectionEnd, replacement);
		commentView.setSelection(selectionStart + replacement.length());
		commentView.requestFocus();
		InputMethodManager inputMethodManager = (InputMethodManager) requireContext()
				.getSystemService(Context.INPUT_METHOD_SERVICE);
		if (inputMethodManager != null) {
			inputMethodManager.showSoftInput(commentView, 0);
		}
	}

	private void insertWrappedMarkup(String open, String close) {
		Editable editable = commentView.getText();
		int length = editable.length();
		int rawSelectionStart = commentView.getSelectionStart();
		int rawSelectionEnd = commentView.getSelectionEnd();
		if (rawSelectionStart < 0) rawSelectionStart = length;
		if (rawSelectionEnd < 0) rawSelectionEnd = rawSelectionStart;
		int selectionStart = Math.min(Math.min(rawSelectionStart, rawSelectionEnd), length);
		int selectionEnd = Math.min(Math.max(rawSelectionStart, rawSelectionEnd), length);
		CharSequence selectedText = editable.subSequence(selectionStart, selectionEnd);
		editable.replace(selectionStart, selectionEnd, open + selectedText + close);
		int contentStart = selectionStart + open.length();
		commentView.setSelection(contentStart, contentStart + selectedText.length());
	}

	private void updateSendButtonState() {
		sendButton.setEnabled(sendButtonEnabled && !attachmentController.isImportInProgress() && captchaController.canSend());
	}

	private String getTextIfVisible(EditText editText) {
		return editText.getVisibility() == View.VISIBLE ? StringUtils.nullIfEmpty(editText.getText().toString()) : null;
	}

	private boolean isCheckedIfVisible(CheckBox checkBox) {
		return checkBox.getVisibility() == View.VISIBLE && checkBox.isChecked();
	}

	// Send boundary: visible fields, password/rating fallback and a copied captcha payload.
	// Keep draft persistence before service handoff and cancel separate from minimize.
	private void submitPostingForm() {
		if (attachmentController.isImportInProgress()) {
			ClickableToast.show(R.string.processing_data__ellipsis);
			return;
		}
		if (sendCoordinator.canSend()) {
			sendCoordinator.send(collectPostingFormData(), this::capturePostDraft, this::captureCaptchaDraft);
		}
	}

	private PostingFormData collectPostingFormData() {
		String subject = getTextIfVisible(subjectView);
		String comment = getTextIfVisible(commentView);
		String name = getTextIfVisible(nameView);
		String email = getTextIfVisible(emailView);
		String password = PostingFormData.resolvePassword(getTextIfVisible(passwordView),
				() -> Preferences.getPassword(Chan.get(getChanName())));
		boolean optionSage = isCheckedIfVisible(sageCheckBox);
		boolean optionSpoiler = isCheckedIfVisible(spoilerCheckBox);
		boolean optionOriginalPoster = isCheckedIfVisible(originalPosterCheckBox);
		String userIcon = iconView.getVisibility() == View.VISIBLE ? getUserIcon() : null;
		PostingAttachmentsController.SendAttachments sendAttachments = attachmentController.createSendAttachments();
		PostingCaptchaController.CaptchaSnapshot captcha = captchaController.snapshot(captchaForm.getInput());
		return new PostingFormData(subject, comment, name, email, password, optionSage, optionSpoiler,
				optionOriginalPoster, userIcon, sendAttachments, captcha);
	}

	public void handleFailResult(PostingService.FailResult failResult) {
		sendCoordinator.handleFailResult(failResult);
	}

	// ActivityResult boundary: preserve request codes, URI order and stale-editor identity checks.
	private void handlePostingActivityResult(int requestCode, int resultCode, Intent data) {
		com.mishiranu.dashchan.ui.UiLifecycleDiagnostics.event(this,
				"activity_result request=" + requestCode + " ok=" + (resultCode == Activity.RESULT_OK)
						+ " view=" + (getView() != null));
		attachmentController.handleActivityResult(requestCode, resultCode, data);
	}

	@Override
	public AttachmentHolder getAttachmentHolder(int index) {
		return attachmentController.getAttachmentHolder(index);
	}

	@Override
	public List<Pair<String, String>> getAttachmentRatingItems() {
		return attachmentController.getAttachmentRatingItems();
	}

	@Override
	public ChanConfiguration.Posting getPostingConfiguration() {
		return postingConfiguration;
	}

	private PostingSendCoordinator.Host createSendHost() {
		return new PostingSendCoordinator.Host() {
			@Override public boolean isResumed() { return PostingFragment.this.isResumed(); }
			@Override public void showPostingState(boolean allowDialog, boolean progressMode, SendPostTask.ProgressState progressState,
					int attachmentIndex, int attachmentsCount) {
				if (allowDialog && progressDialog == null) {
					progressDialog = new ProgressDialog(requireContext(), progressMode ? "%1$d / %2$d kB" : null);
					progressDialog.setOnCancelListener(d -> sendCoordinator.cancel());
					progressDialog.setButton(ProgressDialog.BUTTON_POSITIVE, getString(R.string.minimize),
							(d, w) -> sendCoordinator.minimize());
					progressDialog.setButton(ProgressDialog.BUTTON_NEGATIVE, getString(android.R.string.cancel),
							(d, w) -> sendCoordinator.cancel());
					progressDialog.show();
				}
				if (progressDialog == null) {
					return;
				}
				switch (progressState) {
					case CONNECTING: {
						progressDialog.setMax(1);
						progressDialog.setIndeterminate(true);
						progressDialog.setMessage(getString(R.string.sending__ellipsis));
						break;
					}
					case SENDING: {
						progressDialog.setIndeterminate(false);
						if (progressMode) {
							progressDialog.setMessage(getString(R.string.sending_number_of_number__ellipsis_format,
									attachmentIndex + 1, attachmentsCount));
						} else {
							progressDialog.setMessage(getString(R.string.sending__ellipsis));
						}
						break;
					}
					case PROCESSING: {
						progressDialog.setIndeterminate(false);
						progressDialog.setMessage(getString(R.string.processing_data__ellipsis));
						break;
					}
				}

			}
			@Override public void updatePostingProgress(long progress, long progressMax) {
				if (progressDialog != null) {
					progressDialog.setMax((int) (progressMax / 1000));
					progressDialog.setValue((int) (progress / 1000));
				}
			}
			@Override public void dismissPostingProgress() {
				if (progressDialog != null) progressDialog.dismiss();
				progressDialog = null;
			}
			@Override public void forgetPostingProgress() { progressDialog = null; }
			@Override public void setSendButtonEnabled(boolean enabled) {
				sendButtonEnabled = enabled;
				if (sendButton != null) updateSendButtonState();
			}
			@Override public void closePostingFragment() {
				((FragmentHandler) requireActivity()).removeFragment();
			}
			@Override public void showPostingFailure(PostingService.FailResult failResult) {
				if (failResult.extra != null) {
					ClickableToast.show(failResult.errorItem.toString(), null, new ClickableToast
							.Button(R.string.details, false, () -> new SendPostFailDetailsDialog(failResult.extra)
							.show(getChildFragmentManager(), null)));
				} else {
					ClickableToast.show(failResult.errorItem);
				}
			}
			@Override public void refreshCaptcha(boolean mayShowLoadButton) {
				captchaController.refreshCaptcha(PostingFragment.this, false, mayShowLoadButton, true);
			}
			@Override public void updatePostingConfigurationIfNeeded() { PostingFragment.this.updatePostingConfigurationIfNeeded(); }
		};
	}

	private PostingCaptchaController.Host createCaptchaHost() {
		return new PostingCaptchaController.Host() {
			@Override public void updateSendButtonState() { PostingFragment.this.updateSendButtonState(); }
			@Override public void showLoading() { captchaForm.showLoading(); }
			@Override public void setInput(String text) { captchaForm.setText(text); }
			@Override public void showError(ErrorItem errorItem) {
				ClickableToast.show(errorItem); captchaForm.showError();
			}
			@Override public void updatePostingConfigurationIfNeeded() { PostingFragment.this.updatePostingConfigurationIfNeeded(); }
			@Override public void showCaptcha(ReadCaptchaTask.CaptchaState state, ChanConfiguration.Captcha.Input input,
					Bitmap image, boolean large, boolean blackAndWhite) {
				boolean invertColors = blackAndWhite && !GraphicsUtils
						.isLight(ResourceUtils.getColor(requireContext(), android.R.attr.colorBackground));
				captchaForm.showCaptcha(state, input, image, large, invertColors);
				if (scrollView.getScrollY() + scrollView.getHeight() >= scrollView.getChildAt(0).getHeight()) {
					scrollView.post(() -> {
						if (scrollView != null) {
							scrollView.setScrollY(Math.max(scrollView.getChildAt(0).getHeight() - scrollView.getHeight(), 0));
						}
					});
				}
			}
		};
	}

	private PostingAttachmentsController.Host createAttachmentHost() {
		return new PostingAttachmentsController.Host() {
			@Override public Context requireContext() {
				return PostingFragment.this.requireContext();
			}
			@Override public androidx.fragment.app.FragmentManager getChildFragmentManager() {
				return PostingFragment.this.getChildFragmentManager();
			}
			@Override public androidx.fragment.app.FragmentManager getParentFragmentManager() {
				return PostingFragment.this.getParentFragmentManager();
			}
			@Override public void requestStorage() {
				((FragmentHandler) requireActivity()).requestStorage();
			}
			@Override public void invalidateOptionsMenu() {
				PostingFragment.this.invalidateOptionsMenu();
			}
			@Override public void resizeCommentAfterAttachmentChange() {
				resizeComment(true);
			}
			@Override public void saveDraftAfterAttachmentChange() {
				draftController.storePostDraft(capturePostDraft());
			}
			@Override public void updateSendButtonState() {
				PostingFragment.this.updateSendButtonState();
			}
			@Override public void launchAttachmentPicker(Intent intent) {
				attachmentPicker.launch(intent);
			}
			@Override public void launchImageEditor(Intent intent) {
				imageEditor.launch(intent);
			}
			@Override public void onAttachmentDiagnostic(String event) {
				com.mishiranu.dashchan.ui.UiLifecycleDiagnostics.event(PostingFragment.this, event);
			}
		};
	}

	private void formatQuote() {
		Editable editable = commentView.getText();
		String text = editable.toString();
		int selectionStart = commentView.getSelectionStart();
		int selectionEnd = commentView.getSelectionEnd();
		String selectedText = text.substring(selectionStart, selectionEnd);
		String oneSymbolBefore = text.substring(Math.max(selectionStart - 1, 0), selectionStart);
		if (selectedText.startsWith(">")) {
			String unQuotedText = removeQuoteMarkers(selectedText);
			int diff = selectedText.length() - unQuotedText.length();
			editable.replace(selectionStart, selectionEnd, unQuotedText);
			commentView.setSelection(selectionStart, selectionEnd - diff);
		} else {
			String firstSymbol = oneSymbolBefore.length() == 0 || oneSymbolBefore.equals("\n") ? "" : "\n";
			String quotedText = firstSymbol + "> " + addQuoteMarkers(selectedText);
			int diff = quotedText.length() - selectedText.length();
			editable.replace(selectionStart, selectionEnd, quotedText);
			int newStart = selectionStart + firstSymbol.length();
			int newEnd = selectionEnd + diff;
			if (newEnd - newStart <= 2) {
				newStart = newEnd;
			}
			commentView.setSelection(newStart, newEnd);
		}
	}

	private static String addQuoteMarkers(String text) {
		StringBuilder builder = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			builder.append(c);
			if (c == '\n' && (i + 1 == text.length() || text.charAt(i + 1) != '\n')) builder.append("> ");
		}
		return builder.toString();
	}

	private static String removeQuoteMarkers(String text) {
		StringBuilder builder = new StringBuilder(text.length());
		boolean lineStart = true;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (lineStart && c == '>') {
				if (i + 1 < text.length() && text.charAt(i + 1) == ' ') {
					i++;
				}
				lineStart = false;
				continue;
			}
			builder.append(c);
			lineStart = c == '\n';
		}
		return builder.toString();
	}

	private void resizeComment(boolean post) {
		scrollView.removeCallbacks(resizeComment);
		if (post) {
			scrollView.post(resizeComment);
		} else {
			resizeComment.run();
		}
	}

	private final Runnable resizeComment = () -> {
		if (scrollView != null && commentView != null && scrollView.getWidth() > 0) {
			int availableHeight = scrollView.getHeight() - scrollView.getPaddingTop() - scrollView.getPaddingBottom();
			if (availableHeight <= 0) {
				if (formDiagnostics != null) formDiagnostics.decision("resize_skipped available=" + availableHeight);
				return;
			}
			int padding = commentView.getCompoundPaddingTop() + commentView.getCompoundPaddingBottom();
			if (commentView.getIncludeFontPadding()) {
				Paint.FontMetricsInt metrics = commentView.getPaint().getFontMetricsInt();
				padding += metrics.ascent - metrics.top + metrics.bottom - metrics.descent;
			}
			int lineHeight = Math.max(1, commentView.getLineHeight());
			if (!isCommentKeyboardVisible()) {
				// Measure the natural form height, excluding the current editor height. This
				// accounts for visible fields and attachments without depending on text length.
				View form = scrollView.getChildAt(0);
				int width = scrollView.getWidth() - scrollView.getPaddingLeft() - scrollView.getPaddingRight();
				form.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, width), View.MeasureSpec.EXACTLY),
						View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
				int otherHeight = form.getMeasuredHeight() - commentView.getMeasuredHeight();
				int height = Math.min(availableHeight, Math.max(padding + COMMENT_MIN_LINES * lineHeight,
						availableHeight - otherHeight));
				if (formDiagnostics != null) formDiagnostics.decision("resize ime=false available=" + availableHeight
						+ " other=" + otherHeight + " targetHeight=" + height + " lineHeight=" + lineHeight);
				// Fixed pixel bounds follow available space, not the number of typed lines.
				// Overflow remains scrollable; avoid requesting another layout if bounds are unchanged.
				if (commentView.getMinHeight() != height || commentView.getMaxHeight() != height) {
					commentView.setHeight(height);
				}
				return;
			}
			// Normally grow from four to nine lines. In a short window, even the minimum
			// must fit above the keyboard and the separate send panel.
			int visibleLines = Math.max(1, Math.min(COMMENT_MAX_LINES, (availableHeight - padding) / lineHeight));
			int minLines = Math.min(COMMENT_MIN_LINES, visibleLines);
			if (formDiagnostics != null) formDiagnostics.decision("resize ime=true available=" + availableHeight
					+ " minLines=" + minLines + " maxLines=" + visibleLines + " lineHeight=" + lineHeight);
			if (commentView.getMinLines() != minLines) {
				commentView.setMinLines(minLines);
			}
			if (commentView.getMaxLines() != visibleLines) {
				commentView.setMaxLines(visibleLines);
			}
		}
	};

	private boolean isCommentKeyboardVisible() {
		WindowInsetsCompat insets = scrollView != null ? ViewCompat.getRootWindowInsets(scrollView) : null;
		return insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
	}

	private boolean revealCommentAfterLayout;

	private void scheduleCommentCursorVisibility() {
		if (scrollView != null && commentView != null) {
			revealCommentAfterLayout = true;
			scrollView.invalidate();
		}
	}

	private final ViewTreeObserver.OnPreDrawListener showCommentAfterLayout = () -> {
		if (revealCommentAfterLayout) {
			revealCommentAfterLayout = false;
			if (formDiagnostics != null) formDiagnostics.decision("reveal_requested ime=" + isCommentKeyboardVisible()
					+ " editorFocus=" + (commentView != null && commentView.hasFocus()));
			if (scrollView != null && commentView != null && commentView.hasFocus() && isCommentKeyboardVisible()) {
				int selection = commentView.getSelectionEnd();
				// Revealing only the caret can leave the rest of the editor below the viewport.
				// Use the final laid-out bounds, including the background underline and padding.
				int availableHeight = scrollView.getHeight() - scrollView.getPaddingTop()
						- scrollView.getPaddingBottom();
				if (commentView.getHeight() > 0 && commentView.getHeight() <= availableHeight) {
					// Reveal the caret inside the editor without starting a second, competing
					// parent scroll animation through bringPointIntoView().
					Layout textLayout = commentView.getLayout();
					int textHeight = commentView.getHeight() - commentView.getCompoundPaddingTop()
							- commentView.getCompoundPaddingBottom();
					if (selection >= 0 && textLayout != null && textHeight > 0) {
						int line = textLayout.getLineForOffset(selection);
						int textY = Math.max(commentView.getScrollY(), textLayout.getLineBottom(line) - textHeight);
						textY = Math.min(textY, textLayout.getLineTop(line));
						int maxTextY = Math.max(0, textLayout.getHeight() - textHeight);
						commentView.scrollTo(commentView.getScrollX(), Math.max(0, Math.min(textY, maxTextY)));
					}
					Rect bounds = new Rect();
					commentView.getDrawingRect(bounds);
					scrollView.offsetDescendantRectToMyCoords(commentView, bounds);
					int targetY = scrollView.getScrollY();
					int visibleBottom = targetY + scrollView.getHeight() - scrollView.getPaddingBottom();
					if (bounds.bottom > visibleBottom) {
						targetY += bounds.bottom - visibleBottom;
					}
					if (bounds.top < targetY + scrollView.getPaddingTop()) {
						targetY = bounds.top - scrollView.getPaddingTop();
					}
					if (formDiagnostics != null) formDiagnostics.decision("reveal_apply viewportScroll="
							+ scrollView.getScrollY() + " target=" + Math.max(0, targetY));
					scrollView.scrollTo(scrollView.getScrollX(), Math.max(0, targetY));
				} else if (selection >= 0) {
					// If the window cannot fit even the editor, prioritize the insertion point.
					commentView.bringPointIntoView(selection);
					if (formDiagnostics != null) formDiagnostics.decision("reveal_caret_fallback selection=" + selection);
				}
			}
		}
		return true;
	};

	private class MarkupButtonsBuilder implements View.OnLayoutChangeListener, Runnable {
		private final boolean addPaddingToRoot;
		private int lastWidth;

		public MarkupButtonsBuilder(boolean addPaddingToRoot, int initialWidth) {
			this.addPaddingToRoot = addPaddingToRoot;
			textFormatView.addOnLayoutChangeListener(this);
			lastWidth = initialWidth;
			fillContainer();
		}

		@Override
		public void onLayoutChange(View v, int left, int top, int right, int bottom,
				int oldLeft, int oldTop, int oldRight, int oldBottom) {
			if (textFormatView != null) {
				int width = textFormatView.getWidth();
				if (lastWidth != width) {
					lastWidth = width;
					textFormatView.removeCallbacks(this);
					textFormatView.post(this);
				}
			}
		}

		@Override
		public void run() {
			if (textFormatView != null) {
				fillContainer();
			}
		}

		private int lastSupportedTags;
		private int lastDisplayedTags;
		private int lastServerCommandButtons;

		private void fillContainer() {
			float density = ResourceUtils.obtainDensity(getResources());
			int maxButtonsWidth = lastWidth - textFormatView.getPaddingLeft() - textFormatView.getPaddingRight();
			int buttonMarginLeft = (int) (-4f * density);
			int serverCommandButtons = getServerCommandButtons();
			int serverCommandButtonCount = Integer.bitCount(serverCommandButtons);
			if (serverCommandButtonCount > 0) {
				int commandButtonsWidth = (int) (serverCommandButtonCount
						* SERVER_COMMAND_BUTTON_WIDTH_DP * density)
						+ serverCommandButtonCount * buttonMarginLeft;
				maxButtonsWidth -= commandButtonsWidth;
			}
			Pair<Integer, Integer> supportedAndDisplayedTags = MarkupButtonProvider
					.obtainSupportedAndDisplayedTags(allowPosting ? Chan.get(getChanName()).markup : null,
							getBoardName(), density, maxButtonsWidth, buttonMarginLeft);
			int supportedTags = supportedAndDisplayedTags.first;
			int displayedTags = supportedAndDisplayedTags.second;
			if (lastSupportedTags == supportedTags && lastDisplayedTags == displayedTags
					&& lastServerCommandButtons == serverCommandButtons) {
				return;
			}

			lastSupportedTags = supportedTags;
			lastDisplayedTags = displayedTags;
			lastServerCommandButtons = serverCommandButtons;
			if (commentEditor != null) {
				commentEditor.handleSimilar(supportedTags);
			}
			textFormatView.removeAllViews();
			boolean firstMarkupButton = true;
			for (MarkupButtonProvider provider : MarkupButtonProvider.iterable(displayedTags)) {
				Button button = provider.createButton(textFormatView.getContext(),
						android.R.attr.borderlessButtonStyle);
				ViewUtils.setTextSizeScaled(button, 14);
				LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams
						((int) (provider.widthDp * density), (int) (40f * density));
				if (!firstMarkupButton) {
					layoutParams.leftMargin = buttonMarginLeft;
				}
				button.setTag(provider.tag);
				button.setOnClickListener(formatButtonClickListener);
				button.setPadding(0, 0, 0, 0);
				button.setAllCaps(false);
				provider.applyTextAndStyle(button);
				textFormatView.addView(button, layoutParams);
				firstMarkupButton = false;
			}
			if ((serverCommandButtons & SERVER_COMMAND_BUTTON_MONKEY) != 0) {
				addServerCommandButton(R.drawable.ic_monkey_text, R.string.monkey_command_button,
						COMMAND_MONKEY, density, buttonMarginLeft);
			}
			if ((serverCommandButtons & SERVER_COMMAND_BUTTON_ART_MONKEY) != 0) {
				addServerCommandButton(R.drawable.ic_art_monkey_image, R.string.artmonkey_command_button,
						COMMAND_ART_MONKEY, density, buttonMarginLeft);
			}
			if ((serverCommandButtons & SERVER_COMMAND_BUTTON_APACHAN_VIDEO) != 0) {
				addWrappedMarkupButton(R.drawable.ic_play_circle_outline, R.string.apachan_video_button,
						"[video]", "[/video]", density, buttonMarginLeft);
			}
			textFormatView.setVisibility(textFormatView.getChildCount() > 0 ? View.VISIBLE : View.GONE);

			if (addPaddingToRoot) {
				int padding;
				if (textFormatView.getVisibility() != View.GONE) {
					int measureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
					textFormatView.measure(measureSpec, measureSpec);
					padding = textFormatView.getMeasuredHeight();
				} else {
					padding = 0;
				}
				((ExpandedLayout) getView()).setExtraTop(padding);
			}
		}

		private void addServerCommandButton(int iconResId, int descriptionResId, String command,
				float density, int buttonMarginLeft) {
			ImageButton button = new ImageButton(textFormatView.getContext(), null,
					android.R.attr.borderlessButtonStyle);
			button.setImageResource(iconResId);
			button.setImageTintList(ResourceUtils.getColorStateList(button.getContext(),
					android.R.attr.textColorPrimary));
			button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
			int padding = (int) (8f * density);
			button.setPadding(padding, padding, padding, padding);
			String description = getString(descriptionResId);
			button.setContentDescription(description);
			button.setTooltipText(description);
			button.setOnClickListener(v -> insertServerCommand(command));
			LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
					(int) (SERVER_COMMAND_BUTTON_WIDTH_DP * density), (int) (40f * density));
			if (textFormatView.getChildCount() > 0) {
				layoutParams.leftMargin = buttonMarginLeft;
			}
			textFormatView.addView(button, layoutParams);
		}

		private void addWrappedMarkupButton(int iconResId, int descriptionResId, String open, String close,
				float density, int buttonMarginLeft) {
			ImageButton button = new ImageButton(textFormatView.getContext(), null,
					android.R.attr.borderlessButtonStyle);
			button.setImageResource(iconResId);
			button.setImageTintList(ResourceUtils.getColorStateList(button.getContext(),
					android.R.attr.textColorPrimary));
			button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
			int padding = (int) (8f * density);
			button.setPadding(padding, padding, padding, padding);
			String description = getString(descriptionResId);
			button.setContentDescription(description);
			button.setTooltipText(description);
			button.setOnClickListener(v -> insertWrappedMarkup(open, close));
			LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
					(int) (SERVER_COMMAND_BUTTON_WIDTH_DP * density), (int) (40f * density));
			if (textFormatView.getChildCount() > 0) layoutParams.leftMargin = buttonMarginLeft;
			textFormatView.addView(button, layoutParams);
		}
	}
}
