package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** Source contracts, not a simulation of Android audio focus or another app's resume policy. */
public class VideoAudioFocusContractTest {
	private static final String GALLERY = "ui/gallery/";

	private static String source(String path) throws Exception {
		File root = new File(".").getCanonicalFile();
		while (root != null) {
			File file = new File(root, "src/com/mishiranu/dashchan/" + path + ".java");
			if (file.isFile()) return Files.readString(file.toPath(), StandardCharsets.UTF_8);
			root = root.getParentFile();
		}
		throw new AssertionError("Cannot find source: " + path);
	}

	private static String method(String path, String signature) throws Exception {
		String source = source(path);
		int start = source.indexOf(signature);
		assertTrue("Missing method: " + signature, start >= 0);
		int opening = source.indexOf('{', start);
		int depth = 1;
		int end = opening + 1;
		while (end < source.length() && depth > 0) {
			char c = source.charAt(end++);
			if (c == '{') depth++;
			if (c == '}') depth--;
		}
		assertEquals("Unbalanced method: " + signature, 0, depth);
		return source.substring(opening + 1, end - 1);
	}

	@Test public void videoUsesTransientFocusButMusicRetainsPermanentFocus() throws Exception {
		assertTrue(method("util/AudioFocus", "public static AudioFocus forVideo(")
				.contains("AudioManager.AUDIOFOCUS_GAIN_TRANSIENT"));
		assertTrue(method("util/AudioFocus", "public AudioFocus(Context context, Callback callback)")
				.contains("this(context, AudioManager.AUDIOFOCUS_GAIN, callback)"));
		assertTrue(source("util/AudioFocus").contains("new AudioFocusRequest.Builder(focusGain)"));
		for (String name : new String[] {"VideoUnit", "VideoPipActivity", "VideoPlaybackHandoff"}) {
			String video = source(GALLERY + name);
			assertTrue(name, video.contains("AudioFocus.forVideo("));
			assertFalse(name, video.contains("new AudioFocus("));
		}
		assertTrue(source("content/service/AudioPlayerService").contains("new AudioFocus(this,"));
	}

	@Test public void explicitPauseReleasesFocusEvenWhenDecoderAlreadyStopped() throws Exception {
		String playing = method(GALLERY + "VideoUnit", "private boolean setPlaying(");
		int release = playing.indexOf("audioFocus.release()");
		int stateCheck = playing.indexOf("if (player.isPlaying() != playing)");
		assertTrue(release >= 0 && release < stateCheck);
		assertTrue(playing.contains("if (resetFocus)"));
		assertTrue(playing.contains("playing && player.isAudioPresent() && !muted"));
		assertTrue(playing.contains("pausedByTransientLossOfFocus = false"));
		assertTrue(method(GALLERY + "VideoUnit", "private void markPlaybackFinished(")
				.contains("setPlaying(false, true)"));
	}

	@Test public void pipDoesNotStartAudiblePlaybackAfterDeniedFocus() throws Exception {
		for (String name : new String[] {"attachPlayerView", "continueStandalonePlayback"}) {
			String body = method(GALLERY + "VideoPipActivity", "private void " + name + "(");
			int guard = body.indexOf("startPlaying && !muted && player.isAudioPresent() && !audioFocus.acquire()");
			int pause = body.indexOf("startPlaying = false", guard);
			int play = body.indexOf("player.setPlaying(startPlaying)");
			assertTrue(name, guard >= 0 && pause > guard && play > pause);
		}
	}

	@Test public void handoffTransfersSameRequestWithoutAbandoningSourceFocus() throws Exception {
		String transfer = method("util/AudioFocus", "public void transferTo(");
		assertTrue(transfer.contains("target.session = session"));
		assertTrue(transfer.contains("session = null"));
		assertTrue(transfer.contains("target.session.callback = target.callback"));
		assertFalse(transfer.contains("session.release()"));
		assertFalse(transfer.contains("requestAudioFocus"));
		assertTrue(source(GALLERY + "VideoPlaybackHandoff").contains("source.transferTo(focus)"));
		assertTrue(source(GALLERY + "VideoPlaybackHandoff").contains("focus.transferTo(target)"));
	}

	@Test public void releaseFencesLateCallbacksBeforeAbandoningRequest() throws Exception {
		String release = method("util/AudioFocus", "void release()");
		assertTrue(release.indexOf("acquired = false") >= 0);
		assertTrue(release.indexOf("acquired = false") < release.indexOf("abandonAudioFocusRequest(request)"));
		assertTrue(source("util/AudioFocus").contains("if (acquired)"));
	}
}
