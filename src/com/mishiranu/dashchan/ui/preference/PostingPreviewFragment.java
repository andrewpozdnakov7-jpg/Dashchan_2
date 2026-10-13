package com.mishiranu.dashchan.ui.preference;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import chan.content.ChanMarkup;
import chan.text.CommentEditor;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.ui.ContentFragment;
import com.mishiranu.dashchan.ui.FragmentHandler;
import com.mishiranu.dashchan.ui.posting.PostingCommentSizing;
import com.mishiranu.dashchan.ui.posting.text.MarkupButtonProvider;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.InterfaceAppearance;
import com.mishiranu.dashchan.widget.PostingFormAppearance;
import com.mishiranu.dashchan.widget.ThemeEngine;
import com.mishiranu.dashchan.widget.ViewFactory;

/** Deliberately not a PostingFragment: it cannot send, solve captchas or overwrite real drafts. */
public class PostingPreviewFragment extends ContentFragment {
	private ScrollView scroll;
	private EditText comment;
	private PostingFormAppearance postingAppearance;
	private final Runnable resize = () -> PostingCommentSizing.apply(scroll, comment, null);

	@Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
		return inflater.inflate(R.layout.activity_posting, container, false);
	}

	@Override public void onViewCreated(@NonNull View view, Bundle state) {
		super.onViewCreated(view, state);
		scroll = view.findViewById(R.id.scroll_view);
		comment = view.findViewById(R.id.comment);
		LinearLayout form = view.findViewById(R.id.posting_layout);
		LinearLayout identity = view.findViewById(R.id.personal_data_block);
		EditText name = view.findViewById(R.id.name);
		EditText email = view.findViewById(R.id.email);
		EditText password = view.findViewById(R.id.password);
		EditText subject = view.findViewById(R.id.subject);
		name.setVisibility(Preferences.isPostingNameHidden() ? View.GONE : View.VISIBLE);
		email.setVisibility(Preferences.isPostingEmailHidden() ? View.GONE : View.VISIBLE);
		password.setVisibility(Preferences.isPostingPasswordHidden() ? View.GONE : View.VISIBLE);
		identity.setVisibility(name.getVisibility() == View.VISIBLE || email.getVisibility() == View.VISIBLE
				|| password.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
		subject.setVisibility(Preferences.isPostingSubjectHidden() ? View.GONE : View.VISIBLE);
		view.findViewById(R.id.icon).setVisibility(View.GONE);
		boolean newForm = PostingFormAppearance.isEnabled();
		view.findViewById(R.id.checkbox_parent).setVisibility(newForm ? View.VISIBLE : View.GONE);
		LinearLayout commentParent = view.findViewById(R.id.comment_parent);
		if (newForm) {
			postingAppearance = new PostingFormAppearance(view);
			LinearLayout formatting = new LinearLayout(view.getContext());
			formatting.setOrientation(LinearLayout.HORIZONTAL);
			postingAppearance.setupEditor(commentParent, comment, formatting);
			postingAppearance.decorateFields(name, email, password, subject);
			addPreviewFormatting(formatting);
		} else {
			commentParent.removeView(comment);
			form.addView(comment, form.indexOfChild(commentParent));
			form.removeView(commentParent);
		}
		float density = ResourceUtils.obtainDensity(view);
		form.setPadding(Math.round(8 * density), 0, Math.round(8 * density), 0);
		addHeader(identity, 0, R.string.personal_data);
		if (!newForm) {
			addHeader(form, form.indexOfChild(subject), R.string.message_data);
			InterfaceAppearance.postingForm(view);
			for (EditText field : new EditText[] {name, email, password, subject, comment}) {
				InterfaceAppearance.postingField(field);
			}
		}
		// Test-only fields never read saved identity values, passwords or drafts.
		comment.setMinLines(4);
		comment.setMaxLines(9);
		// View-state restoration later replaces this sample with any edits made in this preview.
		StringBuilder sample = new StringBuilder();
		for (int line = 1; line <= 15; line++) {
			if (line > 1) sample.append('\n');
			sample.append(getString(R.string.posting_preview_sample_line, line));
		}
		comment.setText(sample);
		comment.setSelection(0);
		comment.setOnTouchListener(new View.OnTouchListener() {
			private float previousY;
			@Override public boolean onTouch(View field, MotionEvent event) {
				int action = event.getActionMasked();
				if (action == MotionEvent.ACTION_DOWN) {
					previousY = event.getY();
					scroll.requestDisallowInterceptTouchEvent(true);
				} else if (action == MotionEvent.ACTION_MOVE) {
					float y = event.getY();
					int direction = y < previousY ? 1 : y > previousY ? -1 : 0;
					previousY = y;
					scroll.requestDisallowInterceptTouchEvent(direction != 0 && comment.canScrollVertically(direction));
				} else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
					scroll.requestDisallowInterceptTouchEvent(false);
				}
				return false;
			}
		});
		FrameLayout footer = view.findViewById(R.id.footer_container);
		LinearLayout actions = new LinearLayout(requireContext());
		actions.setOrientation(LinearLayout.VERTICAL);
		TextView notice = new TextView(requireContext());
		ThemeEngine.applyStyle(notice);
		notice.setText(R.string.posting_preview_notice);
		notice.setPadding(Math.round(8 * density), Math.round(8 * density), Math.round(8 * density), 0);
		if (newForm) {
			form.addView(notice, new LinearLayout.LayoutParams(-1, -2));
			getLayoutInflater().inflate(R.layout.activity_posting_footer_common, footer);
			view.findViewById(R.id.captcha_block).setVisibility(View.GONE);
			view.findViewById(R.id.captcha_skip_block).setVisibility(View.VISIBLE);
			view.findViewById(R.id.captcha_cancel).setVisibility(View.GONE);
			TextView captcha = view.findViewById(R.id.captcha_skip_text);
			captcha.setText(R.string.load_captcha);
			view.findViewById(R.id.captcha_block_parent).setOnClickListener(v -> {});
			// Visual sample only: no CaptchaController, network operation or draft storage.
			view.findViewById(R.id.captcha_input).setVisibility(View.GONE);
		} else actions.addView(notice, new LinearLayout.LayoutParams(-1, -2));
		Button send = new Button(requireContext());
		ThemeEngine.applyStyle(send);
		send.setText(R.string.send);
		if (!newForm) InterfaceAppearance.action(send, true);
		// This is the ONLY send-button action: return to the appearance settings.
		send.setOnClickListener(v -> {
			hideKeyboard();
			((FragmentHandler) requireActivity()).removeFragment();
		});
		if (newForm) {
			LinearLayout inputParent = view.findViewById(R.id.captcha_input_parent);
			inputParent.addView(send, new LinearLayout.LayoutParams(-1, -2));
			postingAppearance.finishSetup(send, (CheckBox) view.findViewById(R.id.sage_checkbox),
					(CheckBox) view.findViewById(R.id.spoiler_checkbox),
					(CheckBox) view.findViewById(R.id.original_poster_checkbox));
			postingAppearance.setAttachmentAction(() -> {});
			postingAppearance.setAttachmentAvailable(true);
			PostingFormAppearance.syncFieldVisibility(name, email, password, subject);
		} else {
			actions.addView(send, new LinearLayout.LayoutParams(-1, -2));
			footer.addView(actions, new FrameLayout.LayoutParams(-1, -2));
		}
		scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> scheduleResize());
		comment.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
			if (r - l != or - ol || b - t != ob - ot) scheduleResize();
		});
		ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> { scheduleResize(); return insets; });
		ViewCompat.requestApplyInsets(view);
		scheduleResize();
	}

	private static void addHeader(ViewGroup parent, int index, int text) {
		TextView header = ViewFactory.makeListTextHeader(parent);
		header.setText(text);
		parent.addView(header, index);
		int padding = Math.round(4 * ResourceUtils.obtainDensity(header));
		header.setPadding(padding, 0, padding, 0);
		ViewUtils.setNewMargin(header, 0, 0, 0, -2 * padding);
	}

	private void addPreviewFormatting(LinearLayout formatting) {
		CommentEditor editor = new CommentEditor();
		editor.addTag(ChanMarkup.TAG_BOLD, "[b]", "[/b]");
		editor.addTag(ChanMarkup.TAG_ITALIC, "[i]", "[/i]");
		editor.addTag(ChanMarkup.TAG_UNDERLINE, "[u]", "[/u]");
		editor.addTag(ChanMarkup.TAG_SPOILER, "[spoiler]", "[/spoiler]");
		editor.addTag(ChanMarkup.TAG_QUOTE, "> ", "");
		int tags = ChanMarkup.TAG_BOLD | ChanMarkup.TAG_ITALIC | ChanMarkup.TAG_UNDERLINE
				| ChanMarkup.TAG_SPOILER | ChanMarkup.TAG_QUOTE;
		float density = ResourceUtils.obtainDensity(formatting);
		for (MarkupButtonProvider provider : MarkupButtonProvider.iterable(tags)) {
			Button button = provider.createButton(formatting.getContext(), android.R.attr.borderlessButtonStyle);
			ViewUtils.setTextSizeScaled(button, 14);
			button.setAllCaps(false); button.setPadding(0, 0, 0, 0);
			provider.applyTextAndStyle(button);
			postingAppearance.styleMarkup(button, provider.tag);
			button.setOnClickListener(v -> { editor.formatSelectedText(comment, provider.tag); comment.requestFocus(); });
			formatting.addView(button, new LinearLayout.LayoutParams(Math.round(Math.max(48, provider.widthDp) * density),
					Math.round(48 * density)));
		}
		postingAppearance.formattingVisibility(formatting.getChildCount() > 0);
	}

	private void scheduleResize() {
		if (scroll != null) { scroll.removeCallbacks(resize); scroll.post(resize); }
	}

	private void hideKeyboard() {
		View view = getView();
		if (view != null) {
			InputMethodManager manager = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
			if (manager != null) manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
		}
	}

	@Override public void onViewStateRestored(Bundle state) {
		super.onViewStateRestored(state);
		((FragmentHandler) requireActivity()).setTitleSubtitle(getString(R.string.posting_preview), null);
		scheduleResize();
	}

	@Override public void onDestroyView() {
		hideKeyboard();
		if (postingAppearance != null) { postingAppearance.close(); postingAppearance = null; }
		if (scroll != null) scroll.removeCallbacks(resize);
		ViewCompat.setOnApplyWindowInsetsListener(requireView(), null);
		scroll = null;
		comment = null;
		super.onDestroyView();
	}

	@Override public void onResume() {
		super.onResume();
		if (postingAppearance != null) {
			postingAppearance.activateToolbar(((FragmentHandler) requireActivity()).getToolbarView());
		}
	}

	@Override public void onPause() {
		if (postingAppearance != null) postingAppearance.deactivateToolbar();
		super.onPause();
	}
}
