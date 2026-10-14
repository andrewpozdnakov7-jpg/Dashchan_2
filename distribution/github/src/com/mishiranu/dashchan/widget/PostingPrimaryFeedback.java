package com.mishiranu.dashchan.widget;

/** Confirmation ownership only. Completion releases the click guard, never dispatches a post. */
final class PostingPrimaryFeedback {
	private long generation;
	private boolean active;
	long begin() { active = true; return ++generation; }
	boolean blocksAction() { return active; }
	boolean complete(long owner) {
		if (!active || owner != generation) return false;
		active = false; return true;
	}
	boolean cancel() { boolean released = active; active = false; generation++; return released; }
}
