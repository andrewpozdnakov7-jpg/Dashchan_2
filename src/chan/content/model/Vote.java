package chan.content.model;

import chan.annotation.Public;

@Public
public final class Vote {
	private final int likes;
	private final int dislikes;

	@Public
	public Vote(int likes, int dislikes) {
		this.likes = Math.max(0, likes);
		this.dislikes = Math.max(0, dislikes);
	}

	@Public
	public int getLikes() {
		return likes;
	}

	@Public
	public int getDislikes() {
		return dislikes;
	}

	@Public
	public boolean isShowVotes() {
		return true;
	}
}
