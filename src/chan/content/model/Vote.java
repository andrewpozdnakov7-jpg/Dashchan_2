package chan.content.model;

import chan.annotation.Public;

@Public
public final class Vote {
	private final int likes;
	private final int dislikes;
	private final int userVote;

	@Public
	public Vote(int likes, int dislikes) {
		this(likes, dislikes, 0);
	}

	@Public
	public Vote(int likes, int dislikes, int userVote) {
		this.likes = Math.max(0, likes);
		this.dislikes = Math.max(0, dislikes);
		this.userVote = Math.max(-1, Math.min(1, userVote));
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
	public int getUserVote() {
		return userVote;
	}

	@Public
	public boolean isShowVotes() {
		return true;
	}
}
