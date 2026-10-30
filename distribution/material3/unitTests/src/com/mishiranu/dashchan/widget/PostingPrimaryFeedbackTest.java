package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import org.junit.Test;

public class PostingPrimaryFeedbackTest {
	@Test public void visualConfirmationStaysOwnedOnlyUntilItsOwnCompletion() {
		PostingPrimaryFeedback feedback = new PostingPrimaryFeedback();
		assertFalse(feedback.isActive());
		long owner = feedback.begin(); assertTrue(feedback.isActive());
		assertTrue(feedback.complete(owner)); assertFalse(feedback.isActive());
		assertFalse(feedback.complete(owner));
	}
	@Test public void cancelForErrorPauseOrRestoreMakesDelayedCompletionInert() {
		PostingPrimaryFeedback feedback = new PostingPrimaryFeedback();
		long old = feedback.begin(); assertTrue(feedback.cancel());
		assertFalse(feedback.isActive()); assertFalse(feedback.complete(old));
		long current = feedback.begin();
		assertFalse(feedback.complete(old)); assertTrue(feedback.isActive());
		assertTrue(feedback.complete(current)); assertFalse(feedback.isActive());
	}
	@Test public void newerConfirmationCannotBeReleasedByAnOlderAnimator() {
		PostingPrimaryFeedback feedback = new PostingPrimaryFeedback();
		long old = feedback.begin(), current = feedback.begin();
		assertFalse(feedback.complete(old)); assertTrue(feedback.isActive());
		assertTrue(feedback.complete(current));
		assertFalse(feedback.cancel()); assertFalse(feedback.isActive());
	}
}
