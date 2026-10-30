package com.mishiranu.dashchan.util;

import static org.junit.Assert.*;
import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.view.View;
import androidx.core.view.OneShotPreDrawListener;
import androidx.fragment.app.FragmentTransaction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Ownership/cancellation tests only. Does not change preferences or the system animation scale. */
@RunWith(AndroidJUnit4.class)
public class InterfaceMotionTest {
	@Test public void containerOwnerPreventsSecondListRevealWithoutOwningTheLegacyAnimator() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			RevealView view = new RevealView();
			View root = new View(view.getContext());
			Object owner = new Object();
			root.setTag(R.id.thread_motion_owner, owner); root.setAlpha(0f);
			InterfaceMotion.revealBeforeDraw(view, root, () -> true);
			assertTrue(InterfaceMotion.isAnimating(root));
			view.getViewTreeObserver().dispatchOnPreDraw();
			assertEquals(0, view.hiddenFrames); assertEquals(1f, view.getAlpha(), 0f);
			InterfaceMotion.cancel(root);
			assertSame(owner, root.getTag(R.id.thread_motion_owner));
			assertEquals(0f, root.getAlpha(), 0f);
			root.setTag(R.id.thread_motion_owner, null); root.setAlpha(1f);
			assertFalse(InterfaceMotion.isAnimating(root));
		});
	}

	@Test public void screenOwnerPreventsAnAsyncListFromStartingASecondReveal() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			RevealView view = new RevealView(); View root = new View(view.getContext());
			Object owner = new Object(); root.setTag(R.id.screen_motion_owner, owner);
			InterfaceMotion.revealBeforeDraw(view, root, () -> true);
			view.getViewTreeObserver().dispatchOnPreDraw();
			assertEquals(0, view.hiddenFrames); assertTrue(InterfaceMotion.isAnimating(root));
			InterfaceMotion.cancel(root); assertSame(owner, root.getTag(R.id.screen_motion_owner));
			root.setTag(R.id.screen_motion_owner, null); assertFalse(InterfaceMotion.isAnimating(root));
		});
	}

	@Test public void revealPreparesHiddenFirstFrameBeforeAnimatorStarts() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View view = new View(InstrumentationRegistry.getInstrumentation().getTargetContext());
			Animator animation = InterfaceMotion.reveal(view);
			assertFalse(animation.isStarted());
			assertEquals(0f, view.getAlpha(), 0f);
			assertSame(animation, view.getTag(R.id.interface_motion_animator));
			InterfaceMotion.cancel(view);
			assertEquals(1f, view.getAlpha(), 0f);
		});
	}

	private static final class RevealView extends View {
		int hiddenFrames;
		RevealView() { super(InstrumentationRegistry.getInstrumentation().getTargetContext()); }
		@Override public void setAlpha(float alpha) {
			if (alpha == 0f) hiddenFrames++;
			super.setAlpha(alpha);
		}
	}

	@Test public void readyContentStartsRevealDuringPreDrawOnlyOnce() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			RevealView view = new RevealView();
			View root = new View(view.getContext());
			InterfaceMotion.revealBeforeDraw(view, root, () -> true);
			assertEquals(0, view.hiddenFrames);
			assertEquals(1f, view.getAlpha(), 0f);
			view.getViewTreeObserver().dispatchOnPreDraw();
			assertTrue(view.hiddenFrames > 0);
			InterfaceMotion.cancel(view);
			int hiddenFrames = view.hiddenFrames;
			view.getViewTreeObserver().dispatchOnPreDraw();
			assertEquals(hiddenFrames, view.hiddenFrames);
			assertEquals(1f, view.getAlpha(), 0f);
		});
	}

	@Test public void rootTransitionPreparedAfterSchedulingOwnsFirstAppearance() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			RevealView view = new RevealView();
			View root = new View(view.getContext());
			InterfaceMotion.revealBeforeDraw(view, root, () -> true);
			Animator transition = InterfaceMotion.content(root, true, FragmentTransaction.TRANSIT_FRAGMENT_FADE);
			view.getViewTreeObserver().dispatchOnPreDraw();
			assertEquals(0, view.hiddenFrames);
			assertEquals(1f, view.getAlpha(), 0f);
			assertNull(view.getTag(R.id.interface_motion_animator));
			assertSame(transition, root.getTag(R.id.interface_motion_animator));
			InterfaceMotion.cancel(root);
		});
	}

	@Test public void cancelledOrStaleRevealNeverHidesContent() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			RevealView view = new RevealView();
			View root = new View(view.getContext());
			OneShotPreDrawListener pending = InterfaceMotion.revealBeforeDraw(view, root, () -> true);
			pending.removeListener();
			InterfaceMotion.revealBeforeDraw(view, root, () -> false);
			view.getViewTreeObserver().dispatchOnPreDraw();
			assertEquals(0, view.hiddenFrames);
			assertEquals(1f, view.getAlpha(), 0f);
			assertNull(view.getTag(R.id.interface_motion_animator));
		});
	}

	@Test public void ownedListAnimationIsNotReplacedByPendingReveal() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			RevealView view = new RevealView();
			View root = new View(view.getContext());
			InterfaceMotion.revealBeforeDraw(view, root, () -> true);
			Animator animation = InterfaceMotion.reveal(view);
			int hiddenFrames = view.hiddenFrames;
			view.getViewTreeObserver().dispatchOnPreDraw();
			assertSame(animation, view.getTag(R.id.interface_motion_animator));
			assertEquals(hiddenFrames, view.hiddenFrames);
			InterfaceMotion.cancel(view);
		});
	}

	@Test public void preparedEntranceIsOwnedBeforeStartAndCancellingRestoresVisibleRoot() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View view = new View(InstrumentationRegistry.getInstrumentation().getTargetContext());
			Animator animation = InterfaceMotion.content(view, true, FragmentTransaction.TRANSIT_FRAGMENT_FADE);
			assertNotNull(animation); assertFalse(animation.isStarted());
			assertTrue(InterfaceMotion.isAnimating(view));
			assertEquals(0f, view.getAlpha(), 0f);
			InterfaceMotion.cancel(view);
			assertFalse(InterfaceMotion.isAnimating(view));
			assertNull(view.getTag(R.id.interface_motion_animator));
			assertEquals(1f, view.getAlpha(), 0f); assertEquals(0f, view.getTranslationX(), 0f);
		});
	}

	@Test public void replacingEntranceCancelsOldOwnerAndExplicitCancellationIsIdempotent() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View view = new View(InstrumentationRegistry.getInstrumentation().getTargetContext());
			Animator old = InterfaceMotion.content(view, true, FragmentTransaction.TRANSIT_FRAGMENT_OPEN);
			Animator replacement = InterfaceMotion.reveal(view);
			assertNotSame(old, replacement);
			assertSame(replacement, view.getTag(R.id.interface_motion_animator));
			InterfaceMotion.cancel(view); InterfaceMotion.cancel(view);
			assertEquals(1f, view.getAlpha(), 0f); assertEquals(0f, view.getTranslationX(), 0f);
			InterfaceMotion.cancel(null); assertFalse(InterfaceMotion.isAnimating(null));
		});
	}

	@Test public void noOwnerDoesNotResetUnrelatedViewProperties() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			View view = new View(InstrumentationRegistry.getInstrumentation().getTargetContext());
			view.setAlpha(0.4f); view.setTranslationX(7f);
			ObjectAnimator other = ObjectAnimator.ofFloat(view, View.ALPHA, 0.4f, 0.8f);
			InterfaceMotion.cancel(view);
			assertEquals(0.4f, view.getAlpha(), 0f); assertEquals(7f, view.getTranslationX(), 0f);
			assertFalse(other.isStarted()); assertNull(view.getTag(R.id.interface_motion_animator));
		});
	}
}
