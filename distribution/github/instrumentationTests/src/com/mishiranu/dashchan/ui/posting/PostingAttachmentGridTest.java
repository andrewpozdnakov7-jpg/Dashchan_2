package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.InterfaceMotion;
import com.mishiranu.dashchan.widget.PostingGestureTestActivity;
import com.mishiranu.dashchan.widget.PostingFormAppearance;
import com.mishiranu.dashchan.widget.ThemeEngine;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;

/** Disposable in-memory cards. No files, network, drafts or messages; motion preference is restored. */
@RunWith(AndroidJUnit4.class)
public class PostingAttachmentGridTest {
	private static final class Fixture implements PostingAttachmentsAppearance.Host {
		final List<AttachmentHolder> holders = new ArrayList<>();
		final LinearLayout container;
		final PostingAttachmentsAppearance ui;
		int moves, optionClicks, previewClicks, longPresses, removals;
		AttachmentHolder edited;
		boolean consumeLongPress;
		Fixture(float fontScale) {
			Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
			Configuration config = new Configuration(target.getResources().getConfiguration()); config.fontScale = fontScale;
			Context context = ThemeEngine.attach(target.createConfigurationContext(config)); ThemeEngine.applyTheme(context);
			container = new LinearLayout(context); container.setOrientation(LinearLayout.VERTICAL);
			ui = new PostingAttachmentsAppearance(container, new ScrollView(context), this);
		}
		Fixture(LinearLayout container, ScrollView scroll) {
			this.container = container;
			ui = new PostingAttachmentsAppearance(container, scroll, this);
		}
		void add(int count) {
			for (int i = 0; i < count; i++) {
				AttachmentHolder holder = ui.createCard(v -> optionClicks++, v -> {}, v -> {},
						v -> edited = (AttachmentHolder) v.getTag(), this::remove,
						v -> previewClicks++, v -> { longPresses++; return consumeLongPress; });
				holder.name = "fixture-long-original-file-name-" + i + ".png"; holder.hash = "fixture-" + i;
				holder.fileName.setText(holder.name); holders.add(holder); ui.addCard(holder.view);
				ui.bindFile(holder, "10 kB 1000×2000", true, true, false, true);
			}
		}
		void remove(View view) {
			AttachmentHolder holder = (AttachmentHolder) view.getTag();
			if (!holders.contains(holder)) return;
			ui.prepareRemoval(holder);
			if (holders.remove(holder)) { removals++; rebuild(); }
		}
		void measure(int widthDp) {
			container.requestLayout(); ui.grid.requestLayout();
			container.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(widthDp), View.MeasureSpec.EXACTLY),
					View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
			container.layout(0, 0, container.getMeasuredWidth(), container.getMeasuredHeight());
		}
		@Override public List<AttachmentHolder> attachments() { return holders; }
		@Override public void move(AttachmentHolder holder, int index) {
			holders.remove(holder); holders.add(index, holder); moves++;
			if (!ui.deferRebuild()) rebuild();
		}
		@Override public void rebuild() {
			ui.clearCards(); for (AttachmentHolder holder : holders) ui.addCard(holder.view); ui.prune();
		}
		View actions(AttachmentHolder holder) {
			ViewGroup card = (ViewGroup) holder.view; return card.getChildAt(card.getChildCount() - 1);
		}
		View edit(AttachmentHolder holder) { return ((ViewGroup) actions(holder)).getChildAt(1); }
		View removeButton(AttachmentHolder holder) { return ((ViewGroup) actions(holder)).getChildAt(2); }
	}
	@Test public void compactFooterKeepsTwelveDpToChecksWithoutWindowBottomSlack() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture contextFixture = new Fixture(1);
			Context context = contextFixture.container.getContext();
			LinearLayout root = (LinearLayout) LayoutInflater.from(context).inflate(R.layout.activity_posting, null);
			LayoutInflater.from(context).inflate(R.layout.activity_posting_footer_common,
					(FrameLayout) root.findViewById(R.id.footer_container));
			PostingFormAppearance appearance = new PostingFormAppearance(root);
			Fixture f = new Fixture(root.findViewById(R.id.attachment_container), root.findViewById(R.id.scroll_view));
			try {
				root.findViewById(R.id.personal_data_block).setVisibility(View.GONE);
				EditText editor = root.findViewById(R.id.comment);
				appearance.setupEditor(root.findViewById(R.id.comment_parent), editor, new LinearLayout(context));
				CheckBox sage = root.findViewById(R.id.sage_checkbox), op = root.findViewById(R.id.original_poster_checkbox);
				root.findViewById(R.id.spoiler_checkbox).setVisibility(View.GONE);
				Button send = new Button(context);
				((LinearLayout) root.findViewById(R.id.captcha_input_parent)).addView(send);
				appearance.finishSetup(send, sage, op);
				for (int count : new int[] {1, 4, 0}) {
					f.holders.clear(); f.ui.clearCards(); f.ui.prune(); f.add(count);
					for (int height : new int[] {480, 900}) {
						for (int pass = 0; pass < 3; pass++) {
							root.measure(View.MeasureSpec.makeMeasureSpec(f.ui.dp(400), View.MeasureSpec.EXACTLY),
									View.MeasureSpec.makeMeasureSpec(f.ui.dp(height), View.MeasureSpec.EXACTLY));
							root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
							PostingFormAppearance.prepareEditorLayout(editor, false);
						}
						if (count > 0) {
							Rect card = new Rect(), row = new Rect();
							View last = f.holders.get(count - 1).view, checks = (View) sage.getParent();
							last.getDrawingRect(card); root.offsetDescendantRectToMyCoords(last, card);
							checks.getDrawingRect(row); root.offsetDescendantRectToMyCoords(checks, row);
							assertEquals(f.ui.dp(12), row.top - card.bottom);
							assertSame(root.findViewById(R.id.posting_layout),
									root.findViewById(R.id.posting_action_container).getParent());
						} else {
							assertEquals(0, f.ui.grid.getHeight());
							assertEquals(f.ui.dp(10), ((View) sage.getParent().getParent()).getPaddingTop());
						}
					}
				}
			} finally { f.ui.close(); appearance.close(); contextFixture.ui.close(); }
		});
	}
	@Test public void gridGapIsSixDpTotalAndEmptyFormRestoresItsOriginalMargin() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(1); try {
				LinearLayout form = new LinearLayout(f.container.getContext()); form.setOrientation(LinearLayout.VERTICAL);
				View editor = new View(form.getContext()); editor.setId(R.id.comment_parent);
				LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, f.ui.dp(144));
				params.bottomMargin = f.ui.dp(16); form.addView(editor, params); form.addView(f.container);
				f.add(1);
				assertEquals(0, params.bottomMargin);
				assertEquals(f.ui.dp(6), params.bottomMargin + f.ui.grid.getPaddingTop());
				f.holders.clear(); f.ui.clearCards(); f.ui.prune();
				assertEquals(f.ui.dp(16), params.bottomMargin); assertEquals(0, f.ui.grid.getPaddingTop());
			} finally { f.ui.close(); }
		});
	}
	@Test public void fourCardsHaveTwoRowsAndUncroppedPreviewsWithTwoLargeTargets() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(1); try {
				f.add(4); f.measure(328);
				View a = f.holders.get(0).view, b = f.holders.get(1).view, c = f.holders.get(2).view;
				assertEquals(f.ui.dp(160), a.getWidth()); assertEquals(a.getTop(), b.getTop());
				assertEquals(a.getRight() + f.ui.dp(8), b.getLeft());
				assertEquals(a.getBottom() + f.ui.dp(8), c.getTop()); assertEquals(a.getLeft(), c.getLeft());
				assertEquals(f.ui.dp(144), a.getHeight());
				assertEquals(2 * f.ui.dp(144) + f.ui.dp(8), c.getBottom() - a.getTop());
				for (AttachmentHolder holder : f.holders) {
					assertEquals(ImageView.ScaleType.FIT_CENTER, holder.imageView.getScaleType());
					assertEquals(f.ui.dp(96), holder.imageView.getHeight());
					assertEquals(View.GONE, holder.fileSize.getVisibility());
					ViewGroup actions = (ViewGroup) f.actions(holder); assertEquals(3, actions.getChildCount());
					assertSame(holder.fileName, actions.getChildAt(0));
					assertEquals(f.ui.dp(48), actions.getHeight());
					assertEquals(0, holder.fileName.getLeft());
					assertEquals(f.ui.dp(8), holder.fileName.getPaddingStart());
					assertEquals(a.getWidth() - 2 * f.ui.dp(48), holder.fileName.getWidth());
					assertEquals(holder.fileName.getRight(), f.edit(holder).getLeft());
					assertEquals(f.edit(holder).getRight(), f.removeButton(holder).getLeft());
					assertEquals(actions.getWidth(), f.removeButton(holder).getRight());
					assertEquals(TextUtils.TruncateAt.END, holder.fileName.getEllipsize());
					assertEquals(AttachmentDisplayName.withoutExtension(holder.name), holder.fileName.getText().toString());
					for (int i = 1; i < 3; i++) {
						assertEquals(f.ui.dp(48), actions.getChildAt(i).getWidth());
						assertEquals(f.ui.dp(48), actions.getChildAt(i).getHeight());
					}
					assertEquals(holder.name, holder.fileName.getContentDescription().toString());
				}
				assertEquals(f.ui.dp(12), f.ui.grid.getHeight() - f.holders.get(3).view.getBottom());
			} finally { f.ui.close(); }
		});
	}
	@Test public void tonalActionsKeepFullTargetsAndPressPaintingNeverInvokesAnAction() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(1); try {
				f.add(1); f.measure(328);
				AttachmentHolder holder = f.holders.get(0);
				ImageView edit = (ImageView) f.edit(holder), remove = (ImageView) f.removeButton(holder);
				int nameWidth = holder.fileName.getWidth(), cardHeight = holder.view.getHeight();
				for (ImageView button : new ImageView[] {edit, remove}) {
					assertTrue(button instanceof AttachmentActionButton);
					assertEquals("android.widget.ImageButton", button.getAccessibilityClassName());
					assertEquals(f.ui.dp(48), button.getWidth()); assertEquals(f.ui.dp(48), button.getHeight());
					assertEquals(f.ui.dp(14), button.getPaddingLeft());
					assertNotNull(button.getForeground()); assertNotNull(button.getImageTintList());
					Bitmap paint = Bitmap.createBitmap(button.getWidth(), button.getHeight(), Bitmap.Config.ARGB_8888);
					try {
						button.draw(new Canvas(paint));
						assertEquals(0, Color.alpha(paint.getPixel(0, 0))); // Transparent outside the36dp tonal surface.
						assertEquals(255, Color.alpha(paint.getPixel(f.ui.dp(8), f.ui.dp(24))));
					} finally { paint.recycle(); }
					button.setPressed(true); // Detached fixture settles immediately without animation scheduling.
					assertEquals(1f, button.getScaleX(), 0f); assertEquals(1f, button.getScaleY(), 0f);
					assertEquals(0f, button.getTranslationX(), 0f); assertEquals(0f, button.getTranslationY(), 0f);
					button.setPressed(false);
					assertNull(f.edited); assertEquals(0, f.removals);
				}
				assertEquals(nameWidth, holder.fileName.getWidth()); assertEquals(cardHeight, holder.view.getHeight());
				assertEquals(f.ui.dp(144), cardHeight);
				f.ui.bindFile(holder, "10 kB", true, false, true, false);
				assertFalse(edit.isEnabled()); assertTrue(remove.isEnabled());
				f.ui.bindFile(holder, "10 kB", true, true, false, true);
				assertTrue(edit.isEnabled()); edit.performClick(); assertSame(holder, f.edited);
				remove.performClick(); assertEquals(1, f.removals); assertTrue(f.holders.isEmpty());
			} finally { f.ui.close(); }
		});
	}
	@Test public void emptySingleOddAndNarrowLayoutsDoNotAddPlaceholderCards() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(1); try {
				f.measure(328); assertEquals(0, f.ui.grid.getMeasuredHeight());
				assertEquals(0, PostingAttachmentsAppearance.cardCount(f.container));
				f.add(1); f.measure(328); assertEquals(1, f.ui.grid.getChildCount());
				assertEquals(1, PostingAttachmentsAppearance.cardCount(f.container));
				assertEquals(f.ui.dp(160), f.holders.get(0).view.getWidth());
				f.add(2); f.measure(328); assertEquals(3, f.ui.grid.getChildCount());
				f.measure(288);
				for (AttachmentHolder holder : f.holders) assertEquals(f.ui.dp(288), holder.view.getWidth());
				assertTrue(f.holders.get(1).view.getTop() >= f.holders.get(0).view.getBottom());
			} finally { f.ui.close(); }
		});
	}
	@Test public void accessibleReorderMovesSameHolderRatherThanSwappingFiles() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(1); try {
				f.add(4); f.measure(328); AttachmentHolder first = f.holders.get(0), second = f.holders.get(1), third = f.holders.get(2);
				View drag = ((ViewGroup) first.view).getChildAt(0); // Preview, not a separate handle.
				assertTrue(drag.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
				assertTrue(drag.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
				assertSame(second, f.holders.get(0)); assertSame(third, f.holders.get(1)); assertSame(first, f.holders.get(2));
				assertSame(first.view, f.ui.grid.getChildAt(2)); assertEquals(2, f.moves);
				f.edit(first).performClick(); assertSame(first, f.edited);
				assertEquals("fixture-long-original-file-name-0.png", first.name);
				assertEquals("fixture-0", first.hash);
			} finally { f.ui.close(); }
		});
	}
	@Test public void previewLongPressUsesSameHolderAndKeepsShortTapActions() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(1); try {
				f.add(2); f.measure(328); f.consumeLongPress = true;
				AttachmentHolder holder = f.holders.get(0);
				View stage = ((ViewGroup) holder.view).getChildAt(0);
				assertSame(holder, stage.getTag()); assertTrue(stage.isLongClickable());
				assertTrue(stage.performLongClick()); assertEquals(1, f.longPresses);
				assertEquals(0, f.optionClicks); assertEquals(0, f.previewClicks);
				stage.performClick(); assertEquals(1, f.optionClicks);
				holder.previewButton.setVisibility(View.VISIBLE);
				assertSame(holder, holder.previewButton.getTag());
				assertTrue(holder.previewButton.performLongClick()); assertEquals(2, f.longPresses);
				holder.previewButton.performClick(); assertEquals(1, f.previewClicks);
				f.holders.remove(holder); // A late callback from a removed card must do nothing.
				assertFalse(stage.performLongClick()); assertEquals(2, f.longPresses);
			} finally { f.ui.close(); }
		});
	}
	@Test public void largeTextAndConditionalRatingDoNotCoverActionTargets() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(2); try {
				f.add(2); f.holders.get(0).ratingButton.setVisibility(View.VISIBLE); f.measure(328);
				View a = f.holders.get(0).view, b = f.holders.get(1).view;
				assertEquals(a.getHeight(), b.getHeight());
				for (AttachmentHolder holder : f.holders) {
					ViewGroup actions = (ViewGroup) f.actions(holder);
					assertTrue(actions.getHeight() >= f.ui.dp(48)); assertTrue(actions.getBottom() <= holder.view.getHeight());
					assertTrue(holder.fileName.getHeight() >= holder.fileName.getLineHeight()
							+ holder.fileName.getCompoundPaddingTop() + holder.fileName.getCompoundPaddingBottom());
					assertEquals(1, holder.fileName.getLineCount());
					assertEquals(f.ui.dp(48), f.edit(holder).getWidth());
					assertEquals(f.ui.dp(48), f.removeButton(holder).getWidth());
				}
			} finally { f.ui.close(); }
		});
	}
	@Test public void detachedRemovalIsImmediateAndLateClicksCannotRemoveAnotherAttachment() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Fixture f = new Fixture(1); try {
				f.add(3); f.measure(328);
				AttachmentHolder removed = f.holders.get(0), retained = f.holders.get(1);
				View remove = f.removeButton(removed);
				remove.performClick();
				assertEquals(1, f.removals); assertEquals(2, f.holders.size()); assertSame(retained, f.holders.get(0));
				assertNull(removed.view.getParent()); assertSame(retained.view, f.ui.grid.getChildAt(0));
				remove.performClick(); assertEquals(1, f.removals); assertEquals(2, f.holders.size());
				while (!f.holders.isEmpty()) f.removeButton(f.holders.get(0)).performClick();
				f.measure(328); assertEquals(3, f.removals); assertEquals(0, f.ui.grid.getChildCount());
				assertEquals(0, f.ui.grid.getMeasuredHeight());
			} finally { f.ui.close(); }
		});
	}

	@Test public void liveReflowDoesNotDelayRemovalAndCancellationRestoresRemainingPoses() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
				Fixture f = new Fixture(1);
				try {
					Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, true).close();
					f.add(3); activity.setContentView(f.container); f.measure(328);
					Assume.assumeTrue(f.container.isAttachedToWindow() && f.container.hasWindowFocus() && InterfaceMotion.duration(1) > 0);
					AttachmentHolder removed = f.holders.get(0), retained = f.holders.get(1);
					f.removeButton(removed).performClick();
					assertEquals(2, f.holders.size()); assertEquals(1, f.removals); assertNull(removed.view.getParent());
					f.measure(328);
					// A pending ancestor/decor request is not proof that this grid is stale.
					f.container.getRootView().requestLayout();
					f.ui.grid.getViewTreeObserver().dispatchOnPreDraw();
					assertEquals(f.ui.dp(168), retained.view.getTranslationX(), .1f);
					f.ui.cancelMotion();
					for (AttachmentHolder holder : f.holders) {
						assertEquals(0f, holder.view.getTranslationX(), 0f); assertEquals(0f, holder.view.getTranslationY(), 0f);
						assertEquals(1f, holder.view.getAlpha(), 0f);
					}
					assertEquals(2, f.holders.size()); assertEquals(1, f.removals);
					f.removeButton(retained).performClick();
					assertEquals(1, f.holders.size()); assertEquals(2, f.removals);
					activity.setContentView(new View(activity));
					assertEquals(0f, f.holders.get(0).view.getTranslationX(), 0f);
					assertEquals(1, f.holders.size());
				} finally {
					f.ui.close();
					if (previous instanceof Boolean) Preferences.PREFERENCES.edit()
							.put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) previous).close();
					else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
				}
			});
		}
	}
	@Test public void removalExitCompletesEvenIfTheNextLayoutOrPreDrawNeverBecomesReady() {
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			scenario.onActivity(activity -> {
				Object previous = Preferences.PREFERENCES.getAll().get(Preferences.KEY_NEW_INTERFACE_MOTION);
				Fixture f = new Fixture(1);
				try {
					Preferences.PREFERENCES.edit().put(Preferences.KEY_NEW_INTERFACE_MOTION, true).close();
					f.add(4); activity.setContentView(f.container); f.measure(328);
					Assume.assumeTrue(f.container.isAttachedToWindow() && f.container.hasWindowFocus() && InterfaceMotion.duration(1) > 0);
					AttachmentHolder removed = f.holders.get(0);
					f.removeButton(removed).performClick();
					assertEquals(1, f.removals); assertEquals(3, f.holders.size()); assertNull(removed.view.getParent());
					assertTrue(f.ui.removal.isActive());
					// No layout/pre-draw is dispatched. Ending the real exit clock must still
					// release the pending listener, overlay and image (the old owner had no clock yet).
					f.ui.grid.requestLayout(); f.ui.removal.endAnimation();
					assertFalse(f.ui.removal.isActive());
					f.measure(328); f.ui.grid.getViewTreeObserver().dispatchOnPreDraw();
					assertFalse(f.ui.removal.isActive());
					for (AttachmentHolder holder : f.holders) {
						assertEquals(0f, holder.view.getTranslationX(), 0f); assertEquals(0f, holder.view.getTranslationY(), 0f);
						assertEquals(1f, holder.view.getAlpha(), 0f);
					}
					// Fast consecutive deletes and the last card each have their own finite exit.
					while (!f.holders.isEmpty()) {
						f.removeButton(f.holders.get(0)).performClick();
						f.ui.removal.endAnimation(); assertFalse(f.ui.removal.isActive()); f.measure(328);
					}
					assertEquals(4, f.removals); assertEquals(0, f.ui.grid.getHeight());
				} finally {
					f.ui.close();
					if (previous instanceof Boolean) Preferences.PREFERENCES.edit()
							.put(Preferences.KEY_NEW_INTERFACE_MOTION, (Boolean) previous).close();
					else Preferences.PREFERENCES.edit().remove(Preferences.KEY_NEW_INTERFACE_MOTION).close();
				}
			});
		}
	}
}
