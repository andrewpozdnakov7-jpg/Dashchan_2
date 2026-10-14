package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import org.junit.Test;

public class PostingPrimaryFeedbackTest {
	@Test public void confirmationBlocksOnlyUntilItsOwnCompletion() {
		PostingPrimaryFeedback feedback = new PostingPrimaryFeedback();
		assertFalse(feedback.blocksAction());
		long owner = feedback.begin(); assertTrue(feedback.blocksAction());
		assertTrue(feedback.complete(owner)); assertFalse(feedback.blocksAction());
		assertFalse(feedback.complete(owner));
	}
	@Test public void cancelForErrorPauseOrRestoreMakesDelayedCompletionInert() {
		PostingPrimaryFeedback feedback = new PostingPrimaryFeedback();
		long old = feedback.begin(); assertTrue(feedback.cancel());
		assertFalse(feedback.blocksAction()); assertFalse(feedback.complete(old));
		long current = feedback.begin();
		assertFalse(feedback.complete(old)); assertTrue(feedback.blocksAction());
		assertTrue(feedback.complete(current)); assertFalse(feedback.blocksAction());
	}
	@Test public void newerConfirmationCannotBeReleasedByAnOlderAnimator() {
		PostingPrimaryFeedback feedback = new PostingPrimaryFeedback();
		long old = feedback.begin(), current = feedback.begin();
		assertFalse(feedback.complete(old)); assertTrue(feedback.blocksAction());
		assertTrue(feedback.complete(current));
		assertFalse(feedback.cancel()); assertFalse(feedback.blocksAction());
	}
}
