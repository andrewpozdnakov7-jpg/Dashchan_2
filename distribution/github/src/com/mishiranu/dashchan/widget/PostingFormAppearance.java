package com.mishiranu.dashchan.widget;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.MenuItem;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toolbar;
import androidx.core.graphics.ColorUtils;
import com.google.android.material.loadingindicator.LoadingIndicator;
import chan.content.ChanMarkup;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** View-lifetime presentation only. Keeps the existing editors, callbacks and fixed footer. */
public final class PostingFormAppearance {
	private final View root;
	private final ScrollView scroll;
	private final LinearLayout form;
	private final View actionPanel;
	private final LinearLayout body;
	private final List<Field> fields = new ArrayList<>();
	private final List<View> commands = new ArrayList<>();
	private final List<View> inlineCommands = new ArrayList<>();
	private final List<CompoundButton> checks = new ArrayList<>();
	private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::updateLayout;
	private ThemeEngine.Theme theme;
	private Palette palette;
	private LinearLayout editorContainer;
	private HorizontalScrollView formattingScroll;
	private LinearLayout formatting;
	private LinearLayout editorActions;
	private ImageButton attachButton;
	private Runnable attachmentAction;
	private boolean attachmentAvailable;
	private Button moreButton;
	private View formattingSpacer;
	private PostingFormattingMenu formattingMenu;
	private final Map<View, Integer> markupTags = new IdentityHashMap<>();
	private CarryLayout optionsRow;
	private PostingOptionsPanel optionsPanel;
	private Button captchaEntry;
	private FrameLayout captchaSlot;
	private LoadingIndicator captchaIndicator;
	private PostingCaptchaMotion captchaMotion;
	private com.mishiranu.dashchan.ui.CaptchaForm.CaptchaViewType captchaType;
	private boolean captchaAnswerSaved;
	private PostingSendMotion sendMotion;
	private View captchaRoot;
	private Button send;
	private View spoilerCommand;
	private Drawable captchaActionIcon;
	private boolean captchaActionIconShown;
	private boolean compact;
	private boolean closed;
	private Toolbar toolbar;
	private Drawable toolbarBackground;
	private float toolbarElevation;
	private int toolbarHeight;
	private int toolbarMinimumHeight;
	private View toolbarContainer;
	private Drawable toolbarContainerBackground;
	private float toolbarContainerElevation;
	private final Map<View, Runnable> toolbarRestore = new IdentityHashMap<>();
	private final Map<MenuItem, Drawable> menuIcons = new IdentityHashMap<>();
	private final ViewTreeObserver.OnGlobalLayoutListener toolbarListener = this::styleToolbarTree;
	private final android.graphics.Paint toolbarTextPaint = new android.graphics.Paint();

	public static boolean isEnabled() { return Preferences.isNewPostingFormEnabled(); }

	public PostingFormAppearance(View root) {
		this.root = root;
		scroll = root.findViewById(R.id.scroll_view);
		form = root.findViewById(R.id.posting_layout);
		actionPanel = root.findViewById(R.id.posting_action_container);
		body = (LinearLayout) scroll.getParent();
		root.setTag(R.id.posting_form_owner, this);
		updatePalette();
	}

	private static int dp(View view, float value) { return Math.round(ResourceUtils.obtainDensity(view) * value); }

	private static final class Palette {
		final int background, surface, text, secondary, accent, onAccent, outline, tonal, error, disabled;
		Palette(View view, ThemeEngine.Theme theme) {
			int window = ColorUtils.compositeColors(theme.window,
					theme.base == ThemeEngine.Theme.Base.LIGHT ? Color.WHITE : Color.BLACK);
			background = ColorUtils.blendARGB(InterfaceAppearance.postingBackground(window), theme.accent, .025f);
			surface = InterfaceAppearance.lighterPostingFill(theme.card, background);
			text = InterfaceAppearance.contrasting(ResourceUtils.getColor(view.getContext(),
					android.R.attr.textColorPrimary), surface, 4.5);
			secondary = InterfaceAppearance.contrasting(ColorUtils.blendARGB(
					ColorUtils.blendARGB(surface, text, .65f), theme.accent, .25f), surface, 4.5);
			accent = InterfaceAppearance.contrasting(theme.accent, surface, 3);
			onAccent = InterfaceAppearance.contrasting(Color.WHITE, accent, 4.5);
			outline = InterfaceAppearance.contrasting(ColorUtils.blendARGB(surface, theme.accent, .45f), surface, 3);
			tonal = ColorUtils.blendARGB(surface, accent, .08f);
			error = InterfaceAppearance.contrasting(ResourceUtils.getColor(view.getContext(), R.attr.colorTextError), surface, 3);
			disabled = ColorUtils.blendARGB(surface, accent, .16f);
		}
	}

	private Drawable shape(float radius, int fill, int stroke, float width) {
		GradientDrawable drawable = new GradientDrawable();
		drawable.setColor(fill);
		drawable.setCornerRadius(dp(root, radius));
		if (width > 0) drawable.setStroke(dp(root, width), stroke);
		return drawable;
	}

	private Drawable ripple(Drawable content, float radius) {
		return new RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.accent, 32)),
				content, shape(radius, Color.WHITE, 0, 0));
	}

	private void updatePalette() {
		ThemeEngine.Theme next = ThemeEngine.getTheme(root.getContext());
		if (theme == next) return;
		theme = next;
		palette = new Palette(root, theme);
		root.setBackground(new ColorDrawable(palette.background));
		for (Field field : fields) renderField(field);
		for (View command : commands) renderCommand(command);
		for (CompoundButton check : checks) renderCheck(check);
		if (send != null) renderSend();
		if (formattingScroll != null) formattingScroll.setBackground(shape(14, palette.tonal, 0, 0));
		if (moreButton != null) renderCommand(moreButton);
		if (attachButton != null) renderAttachment();
		if (captchaEntry != null) renderCaptchaEntry();
		styleCaptcha();
		if (toolbar != null) toolbar.setBackground(new ColorDrawable(palette.background));
		if (toolbarContainer != null) toolbarContainer.setBackground(new ColorDrawable(palette.background));
	}

	private static final class Field {
		final PostingFormAppearance owner;
		final EditText input;
		final LinearLayout container;
		final boolean editor;
		boolean error;
		Field(PostingFormAppearance owner, EditText input, LinearLayout container, boolean editor) {
			this.owner = owner; this.input = input; this.container = container; this.editor = editor;
		}
	}

	private Field configureField(EditText input, LinearLayout container, boolean editor) {
		container.setOrientation(LinearLayout.VERTICAL);
		container.setMinimumHeight(dp(root, editor ? 144 : 56));
		container.setPadding(0, 0, 0, 0);
		container.setAddStatesFromChildren(true);
		// Keep the native hint inside every original EditText. Android hides it while text is
		// present (including a restored draft), without an extra label row or a text watcher.
		input.setPadding(dp(root, 16), dp(root, 16), dp(root, 16), dp(root, editor ? 8 : 16));
		ViewUtils.setTextSizeScaled(input, 18);
		if (!editor) { input.setMinHeight(dp(root, 56)); input.setMinimumHeight(dp(root, 56)); }
		Field field = new Field(this, input, container, editor);
		fields.add(field);
		input.setTag(R.id.posting_form_field, field);
		renderField(field);
		container.setVisibility(input.getVisibility());
		return field;
	}

	public void setupEditor(LinearLayout container, EditText input, LinearLayout formatting) {
		editorContainer = container;
		this.formatting = formatting;
		container.removeView(root.findViewById(R.id.comment_format));
		configureField(input, container, true);
		FormattingScrollView adaptiveScroll = new FormattingScrollView(root.getContext());
		adaptiveScroll.onMeasureWidth = this::adaptFormatting;
		formattingScroll = adaptiveScroll;
		formattingScroll.setHorizontalScrollBarEnabled(false);
		formattingScroll.setFillViewport(true);
		formattingScroll.setMinimumHeight(dp(root, 44));
		formattingScroll.setBackground(shape(14, palette.tonal, 0, 0));
		formattingScroll.setClipToOutline(true);
		formattingScroll.setVisibility(View.GONE);
		formatting.setPadding(dp(root, 4), 0, dp(root, 4), 0);
		formatting.setGravity(Gravity.CENTER_VERTICAL);
		formattingScroll.addView(formatting, new FrameLayout.LayoutParams(-2, -2));
		editorActions = new LinearLayout(root.getContext());
		editorActions.setOrientation(LinearLayout.HORIZONTAL);
		editorActions.setGravity(Gravity.CENTER_VERTICAL);
		editorActions.setPadding(dp(root, 8), 0, dp(root, 8), dp(root, 8));
		attachButton = new ImageButton(root.getContext(), null, android.R.attr.borderlessButtonStyle);
		attachButton.setId(R.id.menu_attach);
		attachButton.setImageResource(R.drawable.ic_attach_file_edited);
		attachButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
		attachButton.setPadding(dp(root, 8), dp(root, 8), dp(root, 8), dp(root, 8));
		attachButton.setContentDescription(root.getContext().getString(R.string.attach));
		attachButton.setTooltipText(attachButton.getContentDescription());
		attachButton.setVisibility(View.GONE);
		attachButton.setOnClickListener(v -> {
			if (!closed && attachmentAvailable && attachmentAction != null) attachmentAction.run();
		});
		LinearLayout.LayoutParams attachmentParams = new LinearLayout.LayoutParams(dp(root, 40), dp(root, 44));
		attachmentParams.setMarginEnd(dp(root, 8));
		editorActions.addView(attachButton, attachmentParams);
		editorActions.addView(formattingScroll, new LinearLayout.LayoutParams(0, -2, 1f));
		container.addView(editorActions, new LinearLayout.LayoutParams(-1, -2));
		renderAttachment();
		updateEditorActionsVisibility();
		setGap(container, 16);
	}

	public void setAttachmentAction(Runnable action) {
		attachmentAction = action;
		if (attachButton != null) attachButton.setEnabled(attachmentAvailable && action != null);
	}

	public void setAttachmentAvailable(boolean available) {
		attachmentAvailable = available;
		if (attachButton != null) {
			attachButton.setVisibility(available ? View.VISIBLE : View.GONE);
			attachButton.setEnabled(available && attachmentAction != null);
			updateEditorActionsVisibility();
		}
	}

	private void renderAttachment() {
		attachButton.setBackgroundTintList(null);
		attachButton.setImageTintList(ColorStateList.valueOf(palette.accent));
		attachButton.setMinimumWidth(0); attachButton.setMinimumHeight(0);
		attachButton.setBackground(ripple(shape(14, palette.tonal, 0, 0), 14));
	}

	private void updateEditorActionsVisibility() {
		if (editorActions != null) editorActions.setVisibility(attachButton.getVisibility() != View.GONE
				|| formattingScroll.getVisibility() != View.GONE ? View.VISIBLE : View.GONE);
	}

	/** Own horizontal drags from DOWN, including a reverse drag at either end of the row. */
	static final class FormattingScrollView extends HorizontalScrollView {
		java.util.function.IntConsumer onMeasureWidth;
		private final int touchSlop;
		private float downX, downY;
		private int axis; // 0: undecided, 1: horizontal, 2: vertical.

		FormattingScrollView(android.content.Context context) {
			super(context);
			touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
		}

		@Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
			if (onMeasureWidth != null && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
				onMeasureWidth.accept(Math.max(0, MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight()));
			}
			super.onMeasure(widthMeasureSpec, heightMeasureSpec);
		}

		@Override public boolean dispatchTouchEvent(MotionEvent event) {
			int action = event.getActionMasked();
			if (action == MotionEvent.ACTION_DOWN) {
				downX = event.getX(); downY = event.getY(); axis = 0;
				// Waiting for HorizontalScrollView's interception is too late: the drawer sees
				// the same MOVE first and can steal the drag before the row handles it.
				if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
			} else if (action == MotionEvent.ACTION_MOVE && axis == 0) {
				float dx = Math.abs(event.getX() - downX), dy = Math.abs(event.getY() - downY);
				if (Math.max(dx, dy) > touchSlop) axis = dx > dy ? 1 : 2;
			}
			if (axis == 2 && getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
			try {
				return super.dispatchTouchEvent(event);
			} finally {
				if ((axis == 2 || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
						&& getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
			}
		}

		@Override public boolean onInterceptTouchEvent(MotionEvent event) {
			return axis != 2 && super.onInterceptTouchEvent(event);
		}
	}

	public void decorateFields(EditText... inputs) {
		for (EditText input : inputs) {
			if (input.getTag(R.id.posting_form_field) instanceof Field) continue;
			ViewGroup parent = (ViewGroup) input.getParent();
			int index = parent.indexOfChild(input);
			ViewGroup.LayoutParams previous = input.getLayoutParams();
			LinearLayout container = new LinearLayout(root.getContext());
			parent.removeView(input);
			parent.addView(container, index, previous);
			container.addView(input, new LinearLayout.LayoutParams(-1, -2));
			configureField(input, container, false);
			setGap(container, 12);
		}
	}

	private void setGap(View view, int value) {
		if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
			ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
			params.bottomMargin = dp(root, value);
			view.setLayoutParams(params);
		}
	}

	private void renderField(Field field) {
		field.input.setBackground(null);
		field.input.setBackgroundTintList(null);
		// ErrorEditTextSetter may temporarily install a platform drawable with different padding.
		field.input.setPadding(dp(root, 16), dp(root, 16), dp(root, 16), dp(root, field.editor ? 8 : 16));
		field.input.setTextColor(palette.text);
		field.input.setHintTextColor(field.error ? palette.error : palette.secondary);
		field.input.setHighlightColor(ColorUtils.setAlphaComponent(palette.accent, 64));
		StateListDrawable background = new StateListDrawable();
		float radius = field.editor ? 20 : 16;
		background.addState(new int[] {android.R.attr.state_focused},
				shape(radius, palette.surface, field.error ? palette.error : palette.accent, field.editor ? 1.5f : 1));
		background.addState(new int[] {}, shape(radius, palette.surface,
				field.error ? palette.error : palette.outline, field.error ? 2 : 1));
		field.container.setBackground(background);
		field.container.setPadding(0, 0, 0, 0);
	}

	public static boolean fieldError(EditText view, boolean error) {
		Object tag = view.getTag(R.id.posting_form_field);
		if (!(tag instanceof Field)) return false;
		Field field = (Field) tag;
		field.error = error;
		field.owner.renderField(field);
		return true;
	}

	public static void syncFieldVisibility(EditText... views) {
		for (EditText view : views) {
			Object tag = view.getTag(R.id.posting_form_field);
			if (tag instanceof Field) ((Field) tag).container.setVisibility(view.getVisibility());
		}
	}

	public static void refreshView(View view) {
		Object tag = view.getTag(R.id.posting_form_owner);
		if (tag instanceof PostingFormAppearance) ((PostingFormAppearance) tag).updatePalette();
	}

	public static int editorAvailableHeight(EditText editor, int available) {
		Object tag = editor.getTag(R.id.posting_form_field);
		if (!(tag instanceof Field)) return available;
		Field field = (Field) tag;
		int overhead = Math.max(dp(editor, 32), field.container.getMeasuredHeight() - editor.getMeasuredHeight());
		return Math.max(1, available - overhead);
	}

	/** Minimum text area for the adaptive automatic mode; other user-selected modes stay unchanged. */
	public static int minimumEditorInputHeight(EditText editor) {
		Object tag = editor.getTag(R.id.posting_form_field);
		if (!(tag instanceof Field) || !((Field) tag).editor) return 0;
		Field field = (Field) tag;
		int tools = field.owner.editorActions != null && field.owner.editorActions.getVisibility() != View.GONE
				? Math.max(dp(editor, 52), field.owner.editorActions.getMeasuredHeight()) : 0;
		return Math.max(dp(editor, 144) - tools, editor.getLineHeight()
				+ editor.getCompoundPaddingTop() + editor.getCompoundPaddingBottom());
	}

	public void formattingVisibility(boolean visible) {
		finishCommands();
		formattingScroll.setVisibility(visible ? View.VISIBLE : View.GONE);
		updateEditorActionsVisibility();
	}

	public void styleMarkup(View button, int tag) {
		int label;
		switch (tag) {
			case ChanMarkup.TAG_BOLD: label = R.string.posting_format_bold; break;
			case ChanMarkup.TAG_ITALIC: label = R.string.posting_format_italic; break;
			case ChanMarkup.TAG_UNDERLINE: label = R.string.posting_format_underline; break;
			case ChanMarkup.TAG_OVERLINE: label = R.string.posting_format_overline; break;
			case ChanMarkup.TAG_STRIKE: label = R.string.posting_format_strike; break;
			case ChanMarkup.TAG_CODE: label = R.string.posting_format_code; break;
			case ChanMarkup.TAG_ASCII_ART: label = R.string.posting_format_ascii; break;
			case ChanMarkup.TAG_HEADING: label = R.string.posting_format_heading; break;
			case ChanMarkup.TAG_SUBSCRIPT: label = R.string.posting_format_subscript; break;
			case ChanMarkup.TAG_SUPERSCRIPT: label = R.string.posting_format_superscript; break;
			case ChanMarkup.TAG_SPOILER: label = R.string.posting_format_spoiler; break;
			default: label = R.string.posting_format_quote; break;
		}
		button.setContentDescription(root.getContext().getString(label));
		button.setTooltipText(button.getContentDescription());
		markupTags.put(button, tag);
		if (tag == ChanMarkup.TAG_SPOILER && button instanceof TextView) {
			// The old spoiler span can be black-on-black in a custom theme. This remains a command,
			// not a checked option; the original click handler and tag are untouched.
			TextView text = (TextView) button;
			text.setText(text.getText().toString());
			spoilerCommand = button;
		}
		styleCommand(button);
	}

	public void resetCommands() {
		dismissFormattingMenu();
		commands.clear(); inlineCommands.clear(); markupTags.clear(); spoilerCommand = null;
	}
	public void styleCommand(View button) { commands.add(button); renderCommand(button); }

	private boolean isPrimaryCommand(View command) {
		return inlineCommands.contains(command);
	}

	private void finishCommands() {
		if (formatting == null) return;
		if (moreButton == null) {
			moreButton = new Button(root.getContext(), null, android.R.attr.borderlessButtonStyle);
			moreButton.setId(R.id.posting_format_more);
			moreButton.setText("\u2026");
			moreButton.setAllCaps(false); moreButton.setPadding(0, 0, 0, 0);
			ViewUtils.setTextSizeScaled(moreButton, 16);
			moreButton.setContentDescription(root.getContext().getString(R.string.posting_format_more));
			moreButton.setTooltipText(moreButton.getContentDescription());
			moreButton.setOnClickListener(v -> showFormattingMenu());
		}
		// Reorder only the same presentation widgets; listeners, tags and selection stay owned by posting.
		int position = 0;
		for (int tag : new int[] {ChanMarkup.TAG_BOLD, ChanMarkup.TAG_ITALIC, ChanMarkup.TAG_UNDERLINE,
				ChanMarkup.TAG_OVERLINE, ChanMarkup.TAG_STRIKE, ChanMarkup.TAG_SPOILER, ChanMarkup.TAG_QUOTE}) {
			for (View command : commands) if (Integer.valueOf(tag).equals(markupTags.get(command))) {
				formatting.removeView(command); formatting.addView(command, position++);
			}
		}
		if (formattingSpacer == null) {
			formattingSpacer = new View(root.getContext());
			formattingSpacer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		}
		if (formattingSpacer.getParent() == formatting) formatting.removeView(formattingSpacer);
		if (moreButton.getParent() == formatting) formatting.removeView(moreButton);
		formatting.addView(formattingSpacer, new LinearLayout.LayoutParams(0, 1, 1f));
		formatting.addView(moreButton, new LinearLayout.LayoutParams(dp(root, 36), -2));
		renderCommand(moreButton);
		adaptFormatting(formattingScroll.getWidth() > 0 ? formattingScroll.getWidth() : Integer.MAX_VALUE);
	}

	private int commandMinimumWidth(View view) {
		int textWidth = view instanceof TextView ? (int) Math.ceil(android.text.Layout.getDesiredWidth(
				((TextView) view).getText(), ((TextView) view).getPaint())) + dp(root, 8) : 0;
		return Math.max(dp(root, 36), textWidth);
	}

	private int inlineMinimumWidth() {
		int width = dp(root, 4) * 2 + commandMinimumWidth(moreButton);
		for (View command : inlineCommands) width += commandMinimumWidth(command);
		return width;
	}

	private void adaptFormatting(int available) {
		if (formatting == null || moreButton == null || available <= 0) return;
		inlineCommands.clear();
		for (int tag : new int[] {ChanMarkup.TAG_BOLD, ChanMarkup.TAG_ITALIC, ChanMarkup.TAG_UNDERLINE,
				ChanMarkup.TAG_OVERLINE, ChanMarkup.TAG_STRIKE, ChanMarkup.TAG_SPOILER, ChanMarkup.TAG_QUOTE}) {
			for (View command : commands) if (Integer.valueOf(tag).equals(markupTags.get(command))) inlineCommands.add(command);
		}
		for (int tag : new int[] {ChanMarkup.TAG_OVERLINE, ChanMarkup.TAG_STRIKE, ChanMarkup.TAG_UNDERLINE,
				ChanMarkup.TAG_ITALIC, ChanMarkup.TAG_BOLD, ChanMarkup.TAG_QUOTE, ChanMarkup.TAG_SPOILER}) {
			if (inlineMinimumWidth() <= available) break;
			for (View command : commands) if (Integer.valueOf(tag).equals(markupTags.get(command))) inlineCommands.remove(command);
		}
		boolean extra = inlineCommands.size() < commands.size();
		for (View command : commands) {
			boolean primary = inlineCommands.contains(command);
			command.setVisibility(primary ? View.VISIBLE : View.GONE);
			if (command.getLayoutParams() instanceof LinearLayout.LayoutParams) {
				LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) command.getLayoutParams();
				int width = primary ? commandMinimumWidth(command) : 0;
				float weight = 0f;
				params.leftMargin = params.rightMargin = 0;
				params.setMarginStart(0); params.setMarginEnd(0);
				if (params.width != width || params.weight != weight || params.height != -2) {
					params.width = width; params.height = -2; params.weight = weight; command.setLayoutParams(params);
				}
			}
		}
		moreButton.setVisibility(commands.isEmpty() ? View.GONE : View.VISIBLE);
		moreButton.setEnabled(extra);
		LinearLayout.LayoutParams moreParams = (LinearLayout.LayoutParams) moreButton.getLayoutParams();
		int moreWidth = commandMinimumWidth(moreButton);
		if (moreParams.width != moreWidth || moreParams.height != -2) {
			moreParams.width = moreWidth; moreParams.height = -2; moreButton.setLayoutParams(moreParams);
		}
		ViewGroup.LayoutParams rowParams = formatting.getLayoutParams();
		int rowWidth = available == Integer.MAX_VALUE ? -2 : Math.max(available, inlineMinimumWidth());
		if (rowParams.width != rowWidth) { rowParams.width = rowWidth; formatting.setLayoutParams(rowParams); }
	}

	private void showFormattingMenu() {
		if (closed || !root.isAttachedToWindow()) return;
		dismissFormattingMenu();
		EditText comment = root.findViewById(R.id.comment);
		comment.requestFocus();
		List<View> extra = new ArrayList<>();
		for (View command : commands) {
			if (isPrimaryCommand(command) || !command.isEnabled() || !command.isClickable()) continue;
			extra.add(command);
		}
		if (extra.isEmpty()) return;
		PostingFormattingMenu menu = new PostingFormattingMenu(moreButton, editorActions, extra, markupTags,
				palette.tonal, InterfaceAppearance.contrasting(palette.text, palette.tonal, 4.5),
				InterfaceAppearance.contrasting(palette.accent, palette.tonal, 3), this::performExtraCommand);
		formattingMenu = menu;
		menu.setOnDismissListener(() -> { if (formattingMenu == menu) formattingMenu = null; });
		menu.show();
	}

	boolean performExtraCommand(View command) {
		if (closed || root.getTag(R.id.posting_form_owner) != this || !commands.contains(command)
				|| isPrimaryCommand(command) || !command.isEnabled()) return false;
		// Reacquire editor focus before dispatching the original, unchanged command.
		((EditText) root.findViewById(R.id.comment)).requestFocus();
		return command.performClick();
	}

	private void dismissFormattingMenu() {
		PostingFormattingMenu dialog = formattingMenu;
		formattingMenu = null;
		if (dialog != null) dialog.dismiss();
	}
	private void renderCommand(View button) {
		button.setMinimumWidth(dp(root, 36));
		button.setMinimumHeight(dp(root, 44));
		button.setBackgroundTintList(null);
		button.setBackground(ripple(shape(12, Color.TRANSPARENT, 0, 0), 12));
		if (button instanceof TextView) {
			TextView text = (TextView) button;
			text.setMinWidth(dp(root, 36)); text.setMinHeight(dp(root, 44));
			text.setPadding(0, 0, 0, 0);
			ViewUtils.setTextSizeScaled(text, 16);
			text.setTextColor(palette.text);
		}
		if (button instanceof ImageView) ((ImageView) button).setImageTintList(ColorStateList.valueOf(palette.text));
	}

	public void finishSetup(Button send, CompoundButton... options) {
		this.send = send;
		for (CompoundButton option : options) { checks.add(option); renderCheck(option); }
		FrameLayout footer = root.findViewById(R.id.footer_container);
		View captcha = footer.getChildAt(0);
		captchaRoot = captcha;
		((ViewGroup) send.getParent()).removeView(send);
		footer.removeView(captcha);
		LinearLayout actions = new LinearLayout(root.getContext());
		actions.setOrientation(LinearLayout.VERTICAL);
		optionsRow = root.findViewById(R.id.checkbox_parent);
		((ViewGroup) optionsRow.getParent()).removeView(optionsRow);
		optionsRow.setVerticalSpacing(0);
		optionsRow.setHorizontalSpacing(0);
		List<View> optionViews = new ArrayList<>();
		for (int i = 0; i < optionsRow.getChildCount(); i++) optionViews.add(optionsRow.getChildAt(i));
		optionsRow.removeAllViews();
		for (View option : optionViews) {
			if (option instanceof CompoundButton && !checks.contains(option)) {
				checks.add((CompoundButton) option); renderCheck((CompoundButton) option);
			}
			PostingOptionsPanel.OptionRow row = new PostingOptionsPanel.OptionRow(root.getContext());
			row.addView(option, new FrameLayout.LayoutParams(-1, -2));
			optionsRow.addView(row, new ViewGroup.LayoutParams(-1, -2));
		}
		captchaEntry = new Button(root.getContext(), null, android.R.attr.borderlessButtonStyle);
		captchaEntry.setId(R.id.posting_compact_captcha);
		captchaEntry.setText(R.string.posting_captcha_label);
		captchaEntry.setContentDescription(root.getContext().getString(R.string.load_captcha));
		captchaEntry.setTooltipText(captchaEntry.getContentDescription());
		// The delegated original widget supplies feedback; do not play it twice.
		captchaEntry.setSoundEffectsEnabled(false);
		captchaEntry.setHapticFeedbackEnabled(false);
		captchaEntry.setOnClickListener(v -> {
			View target = root.findViewById(compactCaptchaUsesCancel() ? R.id.captcha_cancel : R.id.captcha_block_parent);
			if (!closed && target != null && target.isClickable()) target.performClick();
		});
		captchaEntry.setOnLongClickListener(v -> {
			View target = root.findViewById(R.id.captcha_block_parent);
			return !closed && target != null && target.performLongClick();
		});
		// Keep the label measured even while loading: the footer and editor must not jump.
		captchaSlot = new FrameLayout(root.getContext());
		captchaSlot.setMinimumWidth(dp(root, 104));
		captchaSlot.setMinimumHeight(dp(root, 56));
		captchaSlot.addView(captchaEntry, new FrameLayout.LayoutParams(-1, -1));
		captchaIndicator = PullLoadingIndicator.createMaterial(root.getContext());
		captchaIndicator.setId(R.id.posting_captcha_loading);
		captchaIndicator.setContainerWidth(dp(root, 32));
		captchaIndicator.setContainerHeight(dp(root, 32));
		captchaIndicator.setIndicatorSize(dp(root, 26));
		captchaIndicator.setContainerColor(Color.TRANSPARENT);
		captchaIndicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		captchaIndicator.setVisibility(View.GONE);
		captchaSlot.addView(captchaIndicator, new FrameLayout.LayoutParams(dp(root, 32), dp(root, 32), Gravity.CENTER));
		captchaMotion = new PostingCaptchaMotion(captchaEntry, captchaSlot, captchaIndicator);
		actions.addView(captcha, new LinearLayout.LayoutParams(-1, -2));
		optionsPanel = new PostingOptionsPanel(root.getContext(), captchaSlot, optionsRow, checks);
		actions.addView(optionsPanel, new LinearLayout.LayoutParams(-1, -2));
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
		params.topMargin = dp(root, 16);
		actions.addView(send, params);
		footer.addView(actions, new FrameLayout.LayoutParams(-1, -2));
		renderSend();
		renderCaptchaEntry();
		styleCaptcha();
		root.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
		updateLayout();
	}

	private void renderCaptchaEntry() {
		captchaEntry.setAllCaps(false);
		ViewUtils.setTextSizeScaled(captchaEntry, 16);
		captchaEntry.setMinWidth(dp(root, 104)); captchaEntry.setMinimumWidth(dp(root, 104));
		captchaEntry.setMinHeight(dp(root, 56)); captchaEntry.setMinimumHeight(dp(root, 56));
		captchaEntry.setPadding(dp(root, 6), 0, dp(root, 6), 0);
		captchaEntry.setStateListAnimator(null); captchaEntry.setElevation(0);
		captchaMotion.colors(palette.surface, palette.tonal,
				InterfaceAppearance.contrasting(palette.accent, palette.tonal, 4.5), palette.accent);
	}

	private boolean compactCaptchaUsesCancel() {
		return captchaAnswerSaved && captchaType != com.mishiranu.dashchan.ui.CaptchaForm.CaptchaViewType.IMAGE;
	}
	private void updateCaptchaEntry() { updateCaptchaEntry(false); }
	private void updateCaptchaEntry(boolean restoring) {
		if (captchaEntry == null || captchaRoot == null) return;
		TextView text = root.findViewById(R.id.captcha_skip_text);
		View skip = root.findViewById(R.id.captcha_skip_block);
		View block = root.findViewById(R.id.captcha_block);
		View cancel = root.findViewById(R.id.captcha_cancel);
		View target = root.findViewById(R.id.captcha_block_parent);
		View loadingView = root.findViewById(R.id.captcha_loading);
		// The cancel child defaults to VISIBLE in XML, but its entire skip block is
		// GONE during LOADING. Its local visibility must not select the legacy loader.
		boolean loading = block.getVisibility() == View.VISIBLE && loadingView.getVisibility() == View.VISIBLE;
		boolean error = captchaType == com.mishiranu.dashchan.ui.CaptchaForm.CaptchaViewType.ERROR;
		boolean answered = captchaAnswerSaved && !loading && !error;
		boolean image = captchaType == com.mishiranu.dashchan.ui.CaptchaForm.CaptchaViewType.IMAGE;
		boolean entry = text != null && skip.getVisibility() == View.VISIBLE
				&& block.getVisibility() != View.VISIBLE && cancel.getVisibility() == View.GONE
				&& root.getContext().getString(R.string.load_captcha).contentEquals(text.getText())
				&& target.isClickable();
		PostingCaptchaMotionState.State state = PostingCaptchaMotionState.resolve(loading, error, answered, entry || image);
		boolean compact = state != PostingCaptchaMotionState.State.LEGACY;
		captchaSlot.setVisibility(compact ? View.VISIBLE : View.GONE);
		captchaMotion.bind(state, restoring);
		CharSequence description = answered ? root.getContext().getString(R.string.posting_captcha_ready_description,
				root.getContext().getString(R.string.posting_captcha_done))
				: error ? root.getContext().getString(R.string.posting_captcha_retry)
				: root.getContext().getString(loading ? R.string.posting_captcha_loading : R.string.load_captcha);
		if (!description.equals(captchaEntry.getContentDescription())) {
			captchaEntry.setContentDescription(description); captchaEntry.setTooltipText(description);
		}
		View action = compactCaptchaUsesCancel() ? cancel : target;
		captchaEntry.setEnabled(action.isEnabled() && action.isClickable());
		// Inline image/input challenges remain visible even when an entered answer is summarized.
		captchaRoot.setVisibility(compact && !image ? View.GONE : View.VISIBLE);
		syncOptionsVisibility();
	}

	/** Observe the existing captcha state without replacing its controller or callbacks. */
	public static void captchaStateChanged(View view) {
		updateCaptchaState(view);
	}

	public static void captchaStateChanged(View view, boolean accepted) {
		// An accepted status alone is not proof of a saved answer.
		updateCaptchaState(view);
	}

	public static void captchaStateChanged(View view, com.mishiranu.dashchan.ui.CaptchaForm.CaptchaViewType type,
			com.mishiranu.dashchan.content.async.ReadCaptchaTask.CaptchaState state, boolean answerSaved, boolean restoring) {
		PostingFormAppearance owner = captchaOwner(view);
		if (owner != null) { owner.captchaType = type; owner.captchaAnswerSaved = answerSaved; owner.updateCaptchaEntry(restoring); }
	}
	private static void updateCaptchaState(View view) {
		PostingFormAppearance owner = captchaOwner(view);
		if (owner != null) { owner.captchaType = null; owner.captchaAnswerSaved = false; owner.updateCaptchaEntry(); }
	}
	private static PostingFormAppearance captchaOwner(View view) {
		while (view != null) {
			Object owner = view.getTag(R.id.posting_form_owner);
			if (owner instanceof PostingFormAppearance) {
				PostingFormAppearance appearance = (PostingFormAppearance) owner;
				return !appearance.closed ? appearance : null;
			}
			view = view.getParent() instanceof View ? (View) view.getParent() : null;
		}
		return null;
	}

	public void syncOptionsVisibility() {
		CarryLayout row = optionsRow != null ? optionsRow : root.findViewById(R.id.checkbox_parent);
		boolean any = false;
		if (optionsPanel != null) {
			for (CompoundButton check : checks) {
				if (check.getParent() instanceof PostingOptionsPanel.OptionRow) {
					((View) check.getParent()).setVisibility(check.getVisibility());
				}
				any |= check.getVisibility() != View.GONE;
			}
		} else for (int i = 0; i < row.getChildCount(); i++) any |= row.getChildAt(i).getVisibility() != View.GONE;
		row.setVisibility(any ? View.VISIBLE : View.GONE);
		if (optionsPanel != null) {
			boolean shown = any || captchaSlot.getVisibility() != View.GONE;
			optionsPanel.setVisibility(shown ? View.VISIBLE : View.GONE);
			LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) optionsPanel.getLayoutParams();
			int gap = shown && captchaRoot.getVisibility() == View.VISIBLE ? dp(root, 12) : 0;
			if (params.topMargin != gap) { params.topMargin = gap; optionsPanel.setLayoutParams(params); }
		}
	}

	private void renderCheck(CompoundButton check) {
		int fullLabel = 0;
		if (check.getId() == R.id.sage_checkbox) {
			check.setText(R.string.dont_bring_up);
			fullLabel = R.string.dont_bring_up;
		} else if (check.getId() == R.id.original_poster_checkbox) {
			check.setText(R.string.original_poster);
			fullLabel = R.string.original_poster;
		}
		if (fullLabel != 0) {
			check.setContentDescription(root.getContext().getString(fullLabel));
			check.setTooltipText(check.getContentDescription());
		}
		ViewUtils.setTextSizeScaled(check, 16);
		check.setTextColor(palette.text);
		// V2 intentionally uses non-overlapping compact touch rows at normal font size.
		// Larger fonts retain at least 48dp and wrap/grow using the original TextView.
		int rowHeight = dp(root, check.getResources().getConfiguration().fontScale > 1f ? 48 : 32);
		check.setMinHeight(rowHeight); check.setMinimumHeight(rowHeight);
		check.setMinWidth(0); check.setMinimumWidth(0);
		check.setPadding(0, 0, 0, 0);
		check.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
		check.setBackgroundTintList(null);
		check.setBackground(ripple(shape(0, Color.TRANSPARENT, 0, 0), 0));
		check.setCompoundDrawablePadding(0);
		check.setSingleLine(false); check.setMaxLines(Integer.MAX_VALUE); check.setEllipsize(null);
		check.setButtonTintList(null);
		StateListDrawable states = new StateListDrawable();
		states.addState(new int[] {android.R.attr.state_checked}, new InsetDrawable(checkboxDrawable(true), 0, 0, dp(root, 12), 0));
		states.addState(new int[] {}, new InsetDrawable(checkboxDrawable(false), 0, 0, dp(root, 12), 0));
		check.setButtonDrawable(states);
	}

	private Drawable checkboxDrawable(boolean checked) {
		GradientDrawable box = new GradientDrawable();
		box.setSize(dp(root, 20), dp(root, 20));
		box.setCornerRadius(dp(root, 3));
		box.setColor(checked ? palette.accent : Color.TRANSPARENT);
		if (!checked) box.setStroke(dp(root, 2), palette.outline);
		if (!checked) return box;
		Drawable tick = ResourceUtils.getDrawable(root.getContext(), R.drawable.ic_editor_done).mutate();
		tick.setTint(palette.onAccent);
		LayerDrawable layers = new LayerDrawable(new Drawable[] {box, tick});
		layers.setLayerSize(0, dp(root, 20), dp(root, 20));
		layers.setLayerSize(1, dp(root, 16), dp(root, 16));
		layers.setLayerGravity(1, Gravity.CENTER);
		return layers;
	}

	private void renderSend() {
		send.setAllCaps(false);
		ViewUtils.setTextSizeScaled(send, 16);
		send.setMinHeight(dp(root, 56)); send.setMinimumHeight(dp(root, 56));
		send.setPadding(dp(root, 16), dp(root, 8), dp(root, 16), dp(root, 8));
		send.setStateListAnimator(null); send.setElevation(0); send.setTranslationZ(0);
		send.setBackgroundTintList(null);
		if (sendMotion == null) sendMotion = new PostingSendMotion(send);
		sendMotion.colors(palette.accent, palette.onAccent, palette.disabled,
				InterfaceAppearance.contrasting(palette.secondary, palette.disabled, 4.5));
	}
	public void updateSendState() { if (sendMotion != null) sendMotion.bind(); }

	private void styleCaptcha() {
		View parent = root.findViewById(R.id.captcha_block_parent);
		if (parent == null) return;
		parent.setMinimumHeight(dp(root, 48));
		parent.setBackground(ripple(shape(16, palette.surface, palette.accent, 1), 16));
		((FrameLayout) parent).setForeground(null);
		TextView text = root.findViewById(R.id.captcha_skip_text);
		text.setAllCaps(false); ViewUtils.setTextSizeScaled(text, 14);
		text.setTextColor(palette.accent);
		ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams)
				root.findViewById(R.id.captcha_skip_block).getLayoutParams();
		params.leftMargin = params.rightMargin = 0;
		root.findViewById(R.id.captcha_skip_block).setLayoutParams(params);
		text.setPadding(dp(root, 12), 0, dp(root, 12), 0);
		captchaActionIcon = ResourceUtils.getDrawable(root.getContext(), R.drawable.ic_refresh).mutate();
		captchaActionIcon.setTint(palette.accent);
		captchaActionIcon.setBounds(0, 0, dp(root, 24), dp(root, 24));
		captchaActionIconShown = !root.getContext().getString(R.string.load_captcha).contentEquals(text.getText());
		updateCaptchaActionIcon();
		updateCaptchaEntry();
		ImageView cancel = root.findViewById(R.id.captcha_cancel);
		cancel.setImageTintList(ColorStateList.valueOf(palette.accent));
		cancel.setContentDescription(root.getContext().getString(R.string.load_captcha));
		ViewGroup.MarginLayoutParams cancelParams = (ViewGroup.MarginLayoutParams) cancel.getLayoutParams();
		cancelParams.leftMargin = cancelParams.rightMargin = 0;
		cancelParams.setMarginStart(0);
		cancel.setLayoutParams(cancelParams);
	}

	private void updateCaptchaActionIcon() {
		TextView text = root.findViewById(R.id.captcha_skip_text);
		if (text == null || captchaActionIcon == null) return;
		boolean show = root.getContext().getString(R.string.load_captcha).contentEquals(text.getText());
		if (show != captchaActionIconShown) {
			captchaActionIconShown = show;
			text.setCompoundDrawablesRelative(show ? captchaActionIcon : null, null, null, null);
			text.setCompoundDrawablePadding(show ? dp(root, 8) : 0);
		}
	}

	private void updateLayout() {
		if (closed) return;
		updatePalette();
		updateCaptchaActionIcon();
		updateCaptchaEntry();
		// Captcha transitions and forum capability changes still own input visibility.
		for (Field field : fields) field.container.setVisibility(field.input.getVisibility());
		int width = scroll.getWidth();
		if (width <= 0) return;
		int horizontal = dp(root, 16)
				+ Math.max(0, (width - dp(root, 600)) / 2);
		int minimumEditor = dp(root, 144);
		if (editorContainer != null) {
			for (Field field : fields) if (field.editor) {
				int overhead = Math.max(dp(root, 32), editorContainer.getMeasuredHeight() - field.input.getMeasuredHeight());
				minimumEditor = Math.max(minimumEditor, overhead + field.input.getLineHeight()
						+ field.input.getCompoundPaddingTop() + field.input.getCompoundPaddingBottom());
			}
		}
		int usableHeight = body.getHeight() - body.getPaddingTop() - body.getPaddingBottom();
		int otherFields = dp(root, 16) + dp(root, 16);
		for (int i = 0; i < form.getChildCount(); i++) {
			View child = form.getChildAt(i);
			if (child != editorContainer && child != actionPanel && child.getVisibility() != View.GONE) {
				otherFields += child.getMeasuredHeight();
				if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
					ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
					otherFields += params.topMargin + params.bottomMargin;
				}
			}
		}
		boolean nextCompact = usableHeight > 0 && usableHeight - actionPanel.getMeasuredHeight() - otherFields < minimumEditor;
		if (nextCompact != compact) {
			ImePanelMotion.finish(root);
			((ViewGroup) actionPanel.getParent()).removeView(actionPanel);
			if (nextCompact) form.addView(actionPanel, new LinearLayout.LayoutParams(-1, -2));
			else body.addView(actionPanel, new LinearLayout.LayoutParams(-1, -2));
			compact = nextCompact;
		}
		// The editor already supplies its 16dp bottom margin. Do not add a second
		// padding before the fixed option row. In scroll fallback this padding
		// remains zero; the footer itself supplies its 16dp bottom-safe-area padding.
		int formBottom = 0;
		if (form.getPaddingLeft() != horizontal || form.getPaddingRight() != horizontal
				|| form.getPaddingTop() != dp(root, 16) || form.getPaddingBottom() != formBottom) {
			form.setPadding(horizontal, dp(root, 16), horizontal, formBottom);
		}
		int actionHorizontal = compact ? 0 : horizontal;
		if (actionPanel.getPaddingLeft() != actionHorizontal || actionPanel.getPaddingRight() != actionHorizontal
				|| actionPanel.getPaddingBottom() != dp(root, 16)) {
			actionPanel.setPadding(actionHorizontal, 0, actionHorizontal, dp(root, 16));
		}
	}

	public void activateToolbar(ViewGroup view) {
		if (!(view instanceof Toolbar) || closed) return;
		if (toolbar == view) { styleToolbarTree(); return; }
		deactivateToolbar();
		toolbar = (Toolbar) view;
		toolbarBackground = toolbar.getBackground(); toolbarElevation = toolbar.getElevation();
		toolbarMinimumHeight = toolbar.getMinimumHeight();
		toolbarHeight = toolbar.getLayoutParams() != null ? toolbar.getLayoutParams().height : -2;
		toolbar.setMinimumHeight(dp(root, 64));
		toolbar.setBackground(new ColorDrawable(palette.background)); toolbar.setElevation(0);
		View container = toolbar.getParent() instanceof View ? (View) toolbar.getParent() : null;
		if (container != null && container.getId() == R.id.toolbar_layout) {
			toolbarContainer = container;
			toolbarContainerBackground = container.getBackground(); toolbarContainerElevation = container.getElevation();
			container.setBackground(new ColorDrawable(palette.background)); container.setElevation(0);
		}
		toolbar.getViewTreeObserver().addOnGlobalLayoutListener(toolbarListener);
		styleToolbarTree();
	}

	private void styleToolbarTree() {
		if (toolbar == null || closed) return;
		styleToolbarChildren(toolbar);
		if (toolbar.getLayoutParams() != null) {
			toolbarTextPaint.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
					22, root.getResources().getDisplayMetrics()));
			android.graphics.Paint.FontMetrics metrics = toolbarTextPaint.getFontMetrics();
			int height = Math.max(dp(root, 64), (int) Math.ceil(metrics.bottom - metrics.top) + dp(root, 16));
			ViewGroup.LayoutParams params = toolbar.getLayoutParams();
			if (params.height != height) { params.height = height; toolbar.setLayoutParams(params); }
		}
		for (int i = 0; i < toolbar.getMenu().size(); i++) {
			MenuItem item = toolbar.getMenu().getItem(i);
			if (!menuIcons.containsKey(item) && item.getIcon() != null && item.getIcon().getConstantState() != null) {
				menuIcons.put(item, item.getIcon());
				Drawable icon = item.getIcon().getConstantState().newDrawable(root.getResources()).mutate();
				icon.setTint(palette.accent); item.setIcon(icon);
			}
		}
	}

	private void styleToolbarChildren(View view) {
		if (!toolbarRestore.containsKey(view)) {
			ColorStateList text = view instanceof TextView ? ((TextView) view).getTextColors() : null;
			ColorStateList image = view instanceof ImageView ? ((ImageView) view).getImageTintList() : null;
			ColorStateList compound = view instanceof TextView ? ((TextView) view).getCompoundDrawableTintList() : null;
			Drawable background = view.getBackground();
			Typeface typeface = view instanceof TextView ? ((TextView) view).getTypeface() : null;
			float textSize = view instanceof TextView ? ((TextView) view).getTextSize() : 0;
			toolbarRestore.put(view, () -> {
				if (view instanceof TextView) {
					((TextView) view).setTextColor(text); ((TextView) view).setCompoundDrawableTintList(compound);
					((TextView) view).setTypeface(typeface);
					((TextView) view).setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSize);
				}
				if (view instanceof ImageView) ((ImageView) view).setImageTintList(image);
				if (view.getId() == R.id.menu_attach) view.setBackground(background);
			});
			if (view instanceof TextView) {
				((TextView) view).setTextColor(palette.text);
				((TextView) view).setCompoundDrawableTintList(ColorStateList.valueOf(palette.accent));
				TextView textView = (TextView) view;
				boolean title = toolbar.getTitle() != null && toolbar.getTitle().length() > 0
						&& toolbar.getTitle().toString().contentEquals(textView.getText());
				if (title) {
					ViewUtils.setTextSizeScaled(textView, 22);
					textView.setTypeface(Typeface.create(typeface, Typeface.NORMAL));
				}
			}
			if (view instanceof ImageView) ((ImageView) view).setImageTintList(ColorStateList.valueOf(palette.text));
			if (view.getId() == R.id.menu_attach) view.setBackground(new InsetDrawable(
					ripple(shape(14, palette.tonal, 0, 0), 14), dp(root, 4)));
		}
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) styleToolbarChildren(group.getChildAt(i));
		}
	}

	public void deactivateToolbar() {
		dismissFormattingMenu();
		if (captchaMotion != null) captchaMotion.finish();
		if (sendMotion != null) sendMotion.finish();
		if (toolbar == null) return;
		if (toolbar.getViewTreeObserver().isAlive()) toolbar.getViewTreeObserver().removeOnGlobalLayoutListener(toolbarListener);
		for (Runnable restore : toolbarRestore.values()) restore.run();
		for (Map.Entry<MenuItem, Drawable> entry : menuIcons.entrySet()) entry.getKey().setIcon(entry.getValue());
		toolbar.setBackground(toolbarBackground); toolbar.setElevation(toolbarElevation);
		toolbar.setMinimumHeight(toolbarMinimumHeight);
		if (toolbar.getLayoutParams() != null) {
			ViewGroup.LayoutParams params = toolbar.getLayoutParams();
			params.height = toolbarHeight; toolbar.setLayoutParams(params);
		}
		if (toolbarContainer != null) {
			toolbarContainer.setBackground(toolbarContainerBackground); toolbarContainer.setElevation(toolbarContainerElevation);
			toolbarContainer = null; toolbarContainerBackground = null;
		}
		toolbarRestore.clear(); menuIcons.clear(); toolbar = null; toolbarBackground = null;
	}

	public void close() {
		deactivateToolbar();
		closed = true;
		if (captchaMotion != null) captchaMotion.close();
		if (sendMotion != null) sendMotion.close();
		if (captchaIndicator != null) captchaIndicator.setVisibility(View.GONE);
		if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
		if (root.getTag(R.id.posting_form_owner) == this) root.setTag(R.id.posting_form_owner, null);
		for (Field field : fields) field.input.setTag(R.id.posting_form_field, null);
		fields.clear(); commands.clear(); inlineCommands.clear(); checks.clear(); markupTags.clear();
		attachmentAction = null;
		spoilerCommand = null; captchaActionIcon = null;
	}
}
