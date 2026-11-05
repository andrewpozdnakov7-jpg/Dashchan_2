package com.mishiranu.dashchan.util;

import java.util.Objects;

/** One reply gesture: normalized helper recovery and a single identity-checked confirmation. */
public final class ReplySwipeRecovery {
	private final Object owner, post;
	private final long revision;
	private final float rawStart, rawEnd, visibleStart;
	private final boolean accepted, valid;
	private boolean canceled, consumed;
	public ReplySwipeRecovery(Object owner, Object post, long revision, float rawStart, float rawEnd,
			float visibleStart, boolean accepted) {
		this.owner = owner; this.post = post; this.revision = revision;
		this.rawStart = rawStart; this.rawEnd = rawEnd; this.visibleStart = visibleStart;
		valid = Float.isFinite(rawStart) && Float.isFinite(rawEnd) && Float.isFinite(visibleStart);
		this.accepted = accepted;
	}
	public float progress(float raw) {
		if (!valid || !Float.isFinite(raw) || rawEnd == rawStart) return 1f;
		return Math.max(0f, Math.min(1f, (raw - rawStart) / (rawEnd - rawStart)));
	}
	public float offset(float raw) { return valid && !canceled ? visibleStart * (1f - progress(raw)) : 0f; }
	public void cancel() { canceled = true; }
	public boolean consume(Object owner, Object post, long revision) {
		if (consumed) return false;
		consumed = true;
		return valid && accepted && !canceled && this.owner == owner && Objects.equals(this.post, post)
				&& this.revision == revision;
	}
}
