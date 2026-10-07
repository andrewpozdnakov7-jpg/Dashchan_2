package com.mishiranu.dashchan.ui.gallery;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** Source contracts only: these do not simulate an OEM's PiP menu or rotation animation. */
public class PipParameterContractTest {
	private static String source() throws Exception {
		File root = new File(".").getCanonicalFile();
		String path = "src/com/mishiranu/dashchan/ui/gallery/VideoPipActivity.java";
		while (root != null) {
			File file = new File(root, path);
			if (file.isFile()) return Files.readString(file.toPath(), StandardCharsets.UTF_8);
			root = root.getParentFile();
		}
		throw new AssertionError("Cannot find the PiP source tree");
	}

	private static String method(String name) throws Exception {
		String source = source();
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
				"(?:private|public|protected) [\\w<>.]+ " + name + "\\(").matcher(source);
		int start = matcher.find() ? matcher.start() : -1;
		assertTrue("Missing method: " + name, start >= 0);
		int opening = source.indexOf('{', start);
		int depth = 1;
		int end = opening + 1;
		while (end < source.length() && depth > 0) {
			char c = source.charAt(end++);
			if (c == '{') depth++;
			if (c == '}') depth--;
		}
		assertEquals("Unbalanced method: " + name, 0, depth);
		return source.substring(opening + 1, end - 1);
	}

	@Test public void controlsAreQueuedAndDeduplicatedWithoutPublishingIntermediateGeometry() throws Exception {
		String controls = method("updatePictureInPictureControls");
		assertTrue(controls.contains("playing == lastPublishedPlaying"));
		assertTrue(controls.contains("seekSeconds == lastPublishedSeekSeconds"));
		assertTrue(controls.contains("schedulePictureInPictureGeometryUpdate()"));
		assertFalse(controls.contains("publishPictureInPictureParams("));
		assertFalse(controls.contains("setSourceRectHint"));
		assertFalse(controls.contains("setAspectRatio"));
		assertFalse(controls.contains("createPictureInPictureParams"));
	}

	@Test public void layoutDoesNotPublishControlsOrFullParams() throws Exception {
		String attach = method("attachPlayerView");
		assertTrue(attach.contains("schedulePictureInPictureGeometryUpdate()"));
		assertFalse(attach.contains("updatePictureInPictureControls()"));
		assertFalse(attach.contains("setPictureInPictureParams("));
	}

	@Test public void geometryPublishesOnlyRealChanges() throws Exception {
		String geometry = method("updatePictureInPictureGeometry");
		assertTrue(geometry.contains("getPictureInPictureSourceRect()"));
		assertTrue(geometry.contains("!aspectChanged && !sourceChanged && !controlsChanged"));
		assertTrue(geometry.contains("if (aspectChanged) builder.setAspectRatio(aspectRatio)"));
		assertTrue(geometry.contains("if (controlsChanged) builder.setActions("));
		assertEquals(1, geometry.split("publishPictureInPictureParams\\(", -1).length - 1);
	}

	@Test public void rotationSchedulesRatherThanImmediatelyPublishing() throws Exception {
		String configuration = method("onConfigurationChanged");
		assertTrue(configuration.contains("schedulePictureInPictureGeometryUpdate()"));
		assertFalse(configuration.contains("setPictureInPictureParams("));
		assertFalse(configuration.contains("updatePictureInPictureGeometry()"));
		String schedule = method("schedulePictureInPictureGeometryUpdate");
		assertTrue(schedule.contains("addOnPreDrawListener(pictureInPictureGeometryPreDraw)"));
		assertFalse(schedule.contains("postDelayed"));
		assertFalse(schedule.contains("aspectRatio.equals(lastPublishedAspectRatio)"));
		assertFalse(source().contains("PICTURE_IN_PICTURE_GEOMETRY_SETTLE_DELAY"));
	}

	@Test public void stoppedAndDestroyedActivitiesCancelGeometryWork() throws Exception {
		String cancel = "cancelPictureInPictureGeometryUpdate()";
		assertTrue(method("onStop").contains(cancel));
		assertTrue(method("onDestroy").contains(cancel));
		assertTrue(method("replacePictureInPictureContent").contains(cancel));
		assertTrue(method("cancelPictureInPictureGeometryUpdate")
				.contains("removeOnPreDrawListener(pictureInPictureGeometryPreDraw)"));
	}

	@Test public void boundsObservationNeverRepairsOrResetsAnything() throws Exception {
		String observation = method("observePictureInPictureBounds");
		assertTrue(observation.contains("pictureInPictureBoundsMonitor.shouldReport("));
		assertTrue(observation.contains("getMaximumWindowMetrics()"));
		for (String forbidden : new String[] {"setSourceRectHint", "setAspectRatio", "setActions",
				"setPlaying(", "releaseVideoView", "setRequestedOrientation", "enterPictureInPictureMode",
				"publishPictureInPictureParams", "startActivity("}) {
			assertFalse(forbidden, observation.contains(forbidden));
		}
		assertFalse(source().contains("recoveryAspectNumerator"));
		assertFalse(source().contains("offscreen_recovery"));
	}

	@Test public void lifecycleAndReplacementCancelObservationCallbacks() throws Exception {
		for (String name : new String[] {"onStop", "onDestroy", "onPictureInPictureModeChanged",
				"replacePictureInPictureContent"}) {
			assertTrue(method(name).contains("cancelPictureInPictureBoundsObservation()"));
		}
		assertTrue(method("cancelPictureInPictureBoundsObservation")
				.contains("handler.removeCallbacks(observeSettledPictureInPictureBounds)"));
		assertTrue(method("onDimensionChange").contains("schedulePictureInPictureGeometryUpdate()"));
	}

	@Test public void everyUpdateReseedsWindowLocalSourceGeometry() throws Exception {
		String publish = method("publishPictureInPictureParams");
		String geometry = method("updatePictureInPictureGeometry");
		assertTrue(geometry.indexOf("getPictureInPictureSourceRect()")
				< geometry.indexOf("publishPictureInPictureParams("));
		assertTrue(geometry.contains("if (sourceRect == null) return false"));
		assertTrue(publish.contains("builder.setSourceRectHint(sourceRect)"));
		assertTrue(publish.contains("lastSourceRectHint.set(sourceRect)"));
		String measure = method("getPictureInPictureSourceRect");
		assertTrue(measure.contains("getLocalVisibleRect(sourceRect)"));
		assertTrue(measure.contains("getLocationInWindow(location)"));
		assertTrue(measure.contains("sourceRect.offset(location[0], location[1])"));
		assertFalse(source().contains("getGlobalVisibleRect"));
	}

	@Test public void unreadyPreDrawDoesNotCancelDrawingOrPollTheWindow() throws Exception {
		String beforeDraw = method("publishPictureInPictureGeometryBeforeDraw");
		assertTrue(beforeDraw.contains("pictureInPictureEntryRequested"));
		assertTrue(beforeDraw.contains("updatePictureInPictureGeometry()"));
		assertTrue(beforeDraw.contains("cancelPictureInPictureGeometryUpdate()"));
		assertFalse(beforeDraw.contains("return false"));
		for (String forbidden : new String[] {"invalidate(", "postDelayed", "setPlaying(",
				"releaseVideoView", "setRequestedOrientation", "enterPictureInPictureMode"}) {
			assertFalse(forbidden, beforeDraw.contains(forbidden));
		}
	}

	@Test public void sourceMustMatchCurrentLayoutAndIntersectDisplay() throws Exception {
		String measure = method("getPictureInPictureSourceRect");
		assertTrue(measure.contains("PipGeometryGate.matchesLayout("));
		assertTrue(measure.contains("PipGeometryGate.intersectsDisplay("));
		assertTrue(measure.contains("rotation != rootView.getDisplay().getRotation()"));
		assertTrue(measure.contains("rootView.isLayoutRequested()"));
		assertTrue(measure.contains("pipVideoView.isLayoutRequested()"));
	}

	@Test public void attachAndRootLayoutRearmButDetachCancelsPublication() throws Exception {
		String create = method("onCreate");
		assertTrue(create.contains("addOnAttachStateChangeListener"));
		assertTrue(create.contains("onViewDetachedFromWindow"));
		assertTrue(create.contains("cancelPictureInPictureGeometryUpdate()"));
		assertTrue(create.contains("rootView.addOnLayoutChangeListener"));
		assertTrue(create.contains("schedulePictureInPictureGeometryUpdate()"));
	}

	@Test public void entryPublishesOnceWithoutChangingThePlayer() throws Exception {
		String entry = method("enterPictureInPicture");
		assertEquals(1, entry.split("enterPictureInPictureMode\\(", -1).length - 1);
		assertFalse(entry.contains("setPictureInPictureParams("));
		assertFalse(entry.contains("setPlaying("));
		assertFalse(entry.contains("releaseVideoView"));
	}

	@Test public void entryRetainsBuilderInputsWithoutAndroid13Getters() throws Exception {
		String entry = method("enterPictureInPicture");
		assertTrue(entry.contains("createPictureInPictureParams(aspectRatio, sourceRect)"));
		assertTrue(entry.contains("lastPublishedAspectRatio = aspectRatio"));
		assertTrue(entry.contains("lastSourceRectHint.set(sourceRect)"));
		assertFalse(source().contains("params.getAspectRatio()"));
		assertFalse(source().contains("params.getSourceRectHint()"));
	}

	@Test public void diagnosticChoiceIsFrozenAndGuardedAtTheFinalPublisher() throws Exception {
		assertTrue(method("onCreate").contains("PipDiagnosticMode.fromPreference(Preferences.getPipDiagnosticMode())"));
		assertEquals(1, source().split("Preferences.getPipDiagnosticMode\\(\\)", -1).length - 1);
		String publish = method("publishPictureInPictureParams");
		assertTrue(publish.indexOf("allowsParameterUpdate(isInPictureInPictureMode())")
				< publish.indexOf("setPictureInPictureParams("));
		assertTrue(method("enterPictureInPicture").contains("allowsHintAtEntry() ? getPictureInPictureSourceRect() : null"));
	}

	@Test public void diagnosticLayoutOnlyUpdatesFreshHintWithoutActionsOrAspectChanges() throws Exception {
		String layout = method("updateDiagnosticPictureInPictureGeometry");
		assertTrue(layout.contains("getPictureInPictureSourceRect()"));
		assertTrue(layout.contains("sourceRect.equals(lastSourceRectHint)"));
		assertFalse(layout.contains("setActions(")); assertFalse(layout.contains("setAspectRatio("));
		String controls = method("updatePictureInPictureControls");
		assertTrue(controls.indexOf("rootView.setKeepScreenOn(playing)") < controls.indexOf("isDiagnostic()"));
		assertTrue(method("togglePlayback").contains("player.setPlaying(playing)"));
		assertTrue(method("seekBy").contains("player.setPosition(position)"));
	}

	@Test public void changingVideoOrReenteringEndsTheDiagnosticTrialWithoutMovingTheWindow() throws Exception {
		assertTrue(method("replacePictureInPictureContent").contains("endPictureInPictureDiagnosticTrial(\"content_replaced\")"));
		assertTrue(method("enterPictureInPicture").contains("endPictureInPictureDiagnosticTrial(\"same_activity_reentry\")"));
		String end = method("endPictureInPictureDiagnosticTrial");
		assertTrue(end.contains("pictureInPictureDiagnosticMode = PipDiagnosticMode.NORMAL"));
		assertTrue(end.contains("pictureInPictureDiagnosticTrialValid = false"));
		for (String forbidden : new String[] {"setAspectRatio", "setActions", "setRequestedOrientation",
				"setPlaying(", "releaseVideoView", "enterPictureInPictureMode", "startActivity("}) {
			assertFalse(forbidden, end.contains(forbidden));
		}
	}
}
