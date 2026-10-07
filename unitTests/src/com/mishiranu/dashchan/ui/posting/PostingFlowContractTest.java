package com.mishiranu.dashchan.ui.posting;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** Source boundaries, not a simulated Fragment, service or ActivityResult registry. */
public class PostingFlowContractTest {
	private static String source() throws Exception {
		return source("PostingFragment");
	}

	private static String source(String className) throws Exception {
		Path root = Path.of(".").toAbsolutePath();
		while (root != null) {
			Path path = root.resolve("src/com/mishiranu/dashchan/ui/posting/" + className + ".java");
			if (Files.isRegularFile(path)) return Files.readString(path, StandardCharsets.UTF_8);
			root = root.getParent();
		}
		throw new AssertionError("Missing posting source: " + className);
	}

	private static String method(String name) throws Exception {
		return method(source(), name);
	}

	private static String attachment(String name) throws Exception {
		return method(source("PostingAttachmentsController"), name);
	}

	private static String captcha(String name) throws Exception {
		return method(source("PostingCaptchaController"), name);
	}
	private static String coordinator(String name) throws Exception {
		return method(source("PostingSendCoordinator"), name);
	}
	private static String form(String name) throws Exception {
		return method(source("PostingFormData"), name);
	}
	private static String draft(String name) throws Exception {
		return method(source("PostingDraftController"), name);
	}

	private static String method(String source, String name) throws Exception {
		Matcher matcher = Pattern.compile("(?m)^\\t(?:private |public |protected )?(?:static )?[\\w<>.]+ "
				+ Pattern.quote(name) + "\\(").matcher(source);
		int start = -1;
		while (matcher.find()) {
			int brace = source.indexOf('{', matcher.end());
			if (brace >= 0 && !source.substring(matcher.end(), brace).contains(";")) {
				start = brace + 1;
				break;
			}
		}
		assertTrue("Missing method body " + name, start >= 0);
		int depth = 1, end = start;
		while (depth > 0 && end < source.length()) {
			char c = source.charAt(end++);
			if (c == '{') depth++;
			if (c == '}') depth--;
		}
		assertEquals("Unbalanced method " + name, 0, depth);
		return source.substring(start, end - 1).replaceAll("\\s+", " ");
	}

	private static void containsAll(String body, String... fragments) {
		for (String fragment : fragments) assertTrue("Missing boundary: " + fragment, body.contains(fragment));
	}

	@Test public void launchersStayInFragmentInPickerThenEditorOrder() throws Exception {
		String source = source();
		int picker = source.indexOf("attachmentPicker = registerForActivityResult(");
		int editor = source.indexOf("imageEditor = registerForActivityResult(");
		int constructor = source.indexOf("public PostingFragment()");
		assertTrue(picker >= 0 && picker < editor && editor < constructor);
		assertEquals(2, source.split("registerForActivityResult\\(", -1).length - 1);
		containsAll(source.substring(picker, editor), "C.REQUEST_CODE_ATTACH", "result.getResultCode()", "result.getData()");
		containsAll(source.substring(editor, constructor), "C.REQUEST_CODE_IMAGE_EDITOR", "result.getResultCode()", "result.getData()");
	}

	@Test public void draftsKeepAllRawFieldsAndAttachmentOrder() throws Exception {
		containsAll(method("capturePostDraft"), "attachmentController.createAttachmentDrafts()", "commentView.getSelectionEnd()",
				"getChanName(), getBoardName(), getThreadNumber(), name, email, password",
				"subject, comment, commentCarriage, attachmentDrafts", "optionSage, optionSpoiler, optionOriginalPoster, userIcon");
		assertFalse(method("capturePostDraft").contains("getTextIfVisible"));
		containsAll(attachment("createAttachmentDrafts"), "for (AttachmentHolder holder : attachments)",
				"holder.hash, holder.name, holder.rating", "holder.optionUniqueHash, holder.optionRemoveMetadata, holder.optionRemoveFileName",
				"holder.optionSpoiler, holder.reencoding");
		containsAll(method("onViewCreated"), "draftController.restorePostDraft(", "attachmentController.restoreAttachmentDrafts(postDraft.attachmentDrafts)");
		containsAll(draft("restorePostDraft"), "store.isPostDraftQueued(draft)", "store.restoreFailedPostDraft(");
		containsAll(attachment("restoreAttachmentDrafts"), "attachmentDraft.hash, attachmentDraft.name, attachmentDraft.rating",
				"attachmentDraft.optionUniqueHash, attachmentDraft.optionRemoveMetadata",
				"attachmentDraft.optionRemoveFileName, attachmentDraft.optionSpoiler", "attachmentDraft.reencoding");
	}

	@Test public void captchaSnapshotAndSavedStateKeepTheirSeparateStorageBoundaries() throws Exception {
		containsAll(method("captureCaptchaDraft"), "captchaController.createDraft(captchaForm.getInput())");
		containsAll(captcha("createDraft"), "captchaType, captchaState, captchaData, loadedCaptchaType",
				"loadedCaptchaInput, loadedCaptchaValidity, input, captchaImage, captchaLarge",
				"captchaBlackAndWhite, captchaLoadTime, boardName, threadNumber");
		containsAll(method("onSaveInstanceState"), "outState.putParcelable(EXTRA_CAPTCHA_DRAFT, captchaDraft)", "saveDraftIfNeeded()");
		containsAll(method("saveDraftIfNeeded"), "draftController.saveDraft(sendCoordinator.isSendSuccess(), getChanName(), this::capturePostDraft, this::captureCaptchaDraft)");
		containsAll(draft("saveDraft"), "if (!sendSuccess && !draftSaved)", "draftSaved = true", "store.storePost(post.get())", "store.storeCaptcha(chan, captcha.get())");
	}

	@Test public void destroyViewSavesBeforeReleasingViewOwnedState() throws Exception {
		String destroy = method("onDestroyView");
		containsAll(destroy, "formDiagnostics.close()", "sendCoordinator.unbind()", "saveDraftIfNeeded()", "attachmentController.detachView()",
				"removeCallbacks(resizeComment)", "removeOnPreDrawListener(showCommentAfterLayout)");
		containsAll(attachment("detachView"), "clearAttachmentDragState()", "task.cancel()", "videoThumbnailTasks.clear()",
				"attachments.clear()", "host = null", "attachmentContainer = null", "scrollView = null",
				"sendButton = null", "attachmentImportViewModel = null", "removeCallbacks(processingNotice)",
				"processingNotice = null", "setOnDragListener(null)");
		for (String field : new String[] {"formDiagnostics", "scrollView", "commentView", "sageCheckBox", "spoilerCheckBox",
				"originalPosterCheckBox", "checkBoxParent", "nameView", "emailView", "passwordView",
				"subjectView", "iconView", "personalDataBlock", "textFormatView", "commentEditWatcher", "captchaForm",
				"sendButton"}) containsAll(destroy, field + " = null");
		assertTrue(destroy.indexOf("saveDraftIfNeeded()") < destroy.indexOf("commentView = null"));
		assertFalse(destroy.contains("cancelSendPost("));
		containsAll(method("bindControllers"), "captchaController.bindState(this)", "attachmentController.bindImportState(this)");
		containsAll(captcha("bindState"), "viewModel.observe(owner.getViewLifecycleOwner(), this)");
		containsAll(destroy, "captchaController.detachView()");
		containsAll(captcha("detachView"), "host = null");
		assertTrue(destroy.indexOf("saveDraftIfNeeded()") < destroy.indexOf("captchaController.detachView()"));
		containsAll(attachment("bindImportState"), "new ViewModelProvider(owner)",
				"attachmentImportViewModel.hasTaskOrValue()",
				"attachmentImportViewModel.observe(owner.getViewLifecycleOwner(), this::onAttachmentImportComplete)");
	}

	@Test public void resumeConsumesDeferredInputsAndClosesAfterSuccess() throws Exception {
		containsAll(method("onResume"), "consumeFuturePostText()", "getFutureAttachmentDrafts()",
				"attachmentController.addFutureAttachments(futureAttachmentDrafts)", "consumeFutureAttachmentDrafts()",
				"sendCoordinator.deliverPendingUiEvents()", "draftController.resetSavedGuard()",
				"if (!allowPosting || sendCoordinator.isSendSuccess())", "removeFragment()");
		containsAll(coordinator("createCallback"), "sendSuccess = true", "if (host.isResumed()) host.closePostingFragment()");
	}

	@Test public void reorderKeepsHolderAndRemoveTargetsTaggedIdentity() throws Exception {
		String move = attachment("moveAttachment");
		containsAll(move, "attachments.indexOf(holder)", "sourceIndex < 0 || sourceIndex == targetIndex",
				"attachments.remove(sourceIndex)", "attachments.add(targetIndex, holder)",
				"invalidateAttachments(true)", "host.saveDraftAfterAttachmentChange()");
		assertTrue(move.indexOf("attachments.remove(sourceIndex)") < move.indexOf("attachments.add(targetIndex, holder)"));
		assertFalse(move.contains("new AttachmentHolder"));
		String source = source("PostingAttachmentsController");
		String remove = source.substring(source.indexOf("private final View.OnClickListener attachmentRemoveListener"),
				source.indexOf("private void invalidateAttachments"));
		containsAll(remove, "(AttachmentHolder) v.getTag()", "attachments.remove(holder)",
				"videoThumbnailTasks.remove(holder)", "task.cancel()", "host.saveDraftAfterAttachmentChange()");
	}

	@Test public void sendFiltersVisibilityAndKeepsPasswordRatingAndOptionFallbacks() throws Exception {
		containsAll(method("getTextIfVisible"), "editText.getVisibility() == View.VISIBLE", "StringUtils.nullIfEmpty");
		containsAll(method("isCheckedIfVisible"), "checkBox.getVisibility() == View.VISIBLE && checkBox.isChecked()");
		String collect = method("collectPostingFormData");
		for (String field : new String[] {"subjectView", "commentView", "nameView", "emailView", "passwordView"})
			containsAll(collect, "getTextIfVisible(" + field + ")");
		containsAll(method("submitPostingForm"), "if (attachmentController.isImportInProgress())", "sendCoordinator.canSend()");
		containsAll(collect, "PostingFormData.resolvePassword(getTextIfVisible(passwordView)",
				"Preferences.getPassword(Chan.get(getChanName()))", "iconView.getVisibility() == View.VISIBLE ? getUserIcon() : null",
				"attachmentController.createSendAttachments()", "isCheckedIfVisible(sageCheckBox)",
				"isCheckedIfVisible(spoilerCheckBox)", "isCheckedIfVisible(originalPosterCheckBox)");
		containsAll(form("resolvePassword"), "password != null ? password : fallback.get()");
		containsAll(attachment("createSendAttachments"),
				"for (int i = 0; i < attachments.size(); i++)", "AttachmentHolder data = attachments.get(i)",
				"if (!found)", "rating = null", "attachmentRatingItems.get(0).first", "if (fileHolder != null)",
				"attachmentHashes.add(data.hash)", "fileHolder, data.name, rating",
				"data.optionUniqueHash, data.optionRemoveMetadata, data.optionRemoveFileName",
				"postingConfiguration.attachmentSpoiler && data.optionSpoiler, data.reencoding",
				"return new SendAttachments(attachments, attachmentHashes)");
	}

	@Test public void sendCopiesCaptchaAndPersistsDraftBeforeServiceHandoff() throws Exception {
		containsAll(method("collectPostingFormData"), "captchaController.snapshot(captchaForm.getInput())",
				"new PostingFormData(subject, comment, name, email, password, optionSage, optionSpoiler, optionOriginalPoster, userIcon, sendAttachments, captcha)");
		containsAll(form("createRequest"), "new ChanPerformer.SendPostData(boardName, threadNumber, subject, comment, name, email, password",
				"sage, spoiler, originalPoster, userIcon, captcha.type", "captcha.data.copy()", "captcha.needLoad, 15000, 45000");
		containsAll(coordinator("send"), "form.createRequest(boardName, threadNumber)", "drafts.storeForSend(draft, chanName, captcha)",
				"allowDialog = false", "form.createAttachmentHashes()", "form.allowFloodRetry()", "minimize()", "drafts.resetSavedGuard()");
		containsAll(form("allowFloodRetry"), "captcha.state == ReadCaptchaTask.CaptchaState.PASS");
		containsAll(captcha("snapshot"), "loadedCaptchaType != null ? loadedCaptchaType : captchaType",
				"captchaData.copy()", "data.put(ChanPerformer.CaptchaData.INPUT, userInput)");
		assertTrue(source("PostingCaptchaController").contains("ReadCaptchaTask.CaptchaState.MAY_LOAD"));
		assertTrue(source("PostingCaptchaController").contains("ReadCaptchaTask.CaptchaState.MAY_LOAD_SOLVING"));
		containsAll(draft("storeForSend"), "store.storePost(postDraft)", "store.storeCaptcha(chan, captcha.get())", "draftSaved = true");
		assertTrue(coordinator("send").indexOf("drafts.storeForSend(") < coordinator("send").indexOf("postingBinder.send("));
		assertTrue(captcha("snapshot").indexOf("captchaData.copy()") < captcha("snapshot").indexOf("data.put("));
	}

	@Test public void cancelAndMinimizeRemainDifferentActions() throws Exception {
		containsAll(coordinator("cancel"), "postingBinder.cancel(chanName, boardName, threadNumber)");
		containsAll(coordinator("minimize"), "host.closePostingFragment()");
		assertFalse(coordinator("minimize").contains("cancel("));
		assertFalse(coordinator("unbind").contains("cancel("));
		containsAll(method("handleFailResult"), "sendCoordinator.handleFailResult(failResult)");
		containsAll(coordinator("handleFailResult"), "host.isResumed()", "failResult = failure",
				"failure.errorItem.httpResponseCode == 0 && !failure.keepCaptcha", "host.refreshCaptcha(!failure.captchaError)");
		containsAll(coordinator("deliverPendingUiEvents"), "failResult = null", "handleFailResult(pending)");
		containsAll(coordinator("unbind"), "releaseEndpoint()", "previous.unbind(connection)",
				"postingCallback = null", "host = null", "bindingGeneration++");
		containsAll(source("PostingSendCoordinator"), "Context.BIND_AUTO_CREATE", "postingBinder.register(callback, chanName, boardName, threadNumber)",
				"postingBinder.unregister(postingCallback)", "generation == bindingGeneration");
		assertFalse(source().contains("private PostingService.Binder"));
		assertFalse(source().contains("new ChanPerformer.SendPostData("));
	}

	@Test public void pickerAndEditorKeepOrderedUrisPermissionsAndStaleResultGuard() throws Exception {
		containsAll(method("handlePostingActivityResult"), "attachmentController.handleActivityResult(requestCode, resultCode, data)");
		String result = attachment("handleActivityResult");
		containsAll(result, "resultCode == Activity.RESULT_OK && data != null", "case C.REQUEST_CODE_ATTACH",
				"Intent.FLAG_GRANT_READ_URI_PERMISSION", "LinkedHashSet<Uri> uris", "uris.add(dataUri)", "uris.add(uri)",
				"case C.REQUEST_CODE_IMAGE_EDITOR", "AttachmentResultGuard.matches(", "EXTRA_RESULT_SOURCE_HASH",
				"EXTRA_RESULT_SOURCE_NAME", "holder.hash, holder.name", "holder.hash = hash", "holder.name = name");
		assertTrue(result.indexOf("AttachmentResultGuard.matches(") < result.indexOf("holder.hash = hash"));
	}

	@Test public void subsystemStateHasSingleOwnersAndSnapshotsDoNotRetainUi() throws Exception {
		String fragment = source();
		for (String field : new String[] {"attachments", "videoThumbnailTasks", "draggedAttachment",
				"attachmentDragTarget", "attachmentColumnCount", "attachmentImportInProgress", "attachmentImportViewModel",
				"captchaType", "captchaState", "captchaData", "loadedCaptchaType", "loadedCaptchaInput",
				"loadedCaptchaValidity", "captchaImage", "captchaLarge", "captchaBlackAndWhite", "captchaLoadTime",
				"draftSaved", "postingBinder", "postingConnection", "postingCallback", "sendSuccess", "failResult", "allowDialog"}) {
			assertFalse("Duplicate Fragment state: " + field, Pattern.compile(
					"(?m)^\\tprivate (?:final )?[\\w.<>]+ " + Pattern.quote(field) + "[; =]").matcher(fragment).find());
		}
		containsAll(source("PostingAttachmentsController"), "ArrayList<AttachmentHolder> attachments = new ArrayList<>()",
				"HashMap<AttachmentHolder, VideoThumbnailTask> videoThumbnailTasks");
		containsAll(source("PostingCaptchaController"), "private ReadCaptchaTask.CaptchaState captchaState;",
				"private ChanPerformer.CaptchaData captchaData;");
		containsAll(source("PostingDraftController"), "private boolean draftSaved;");
		containsAll(source("PostingSendCoordinator"), "private boolean sendSuccess;", "private PostingService.FailResult failResult;");
		for (String owner : new String[] {"PostingDraftController", "PostingSendCoordinator", "PostingFormData"}) {
			String text = source(owner);
			for (String forbidden : new String[] {"import android.view.", "import android.widget.", "private PostingFragment",
					"private Fragment", "CaptchaForm", "AttachmentHolder"}) assertFalse(owner + ": " + forbidden, text.contains(forbidden));
		}
		for (String peer : new String[] {"PostingAttachmentsController", "PostingCaptchaController", "PostingSendCoordinator"})
			assertFalse(source("PostingDraftController").contains(peer));
		for (String peer : new String[] {"PostingAttachmentsController", "PostingDraftController", "PostingSendCoordinator"})
			assertFalse(source("PostingCaptchaController").contains(peer));
		assertFalse(source("PostingSendCoordinator").contains("PostingAttachmentsController"));
		assertFalse(source("PostingSendCoordinator").contains("PostingCaptchaController"));
		containsAll(source("PostingFormData"), "Collections.unmodifiableList", "captcha.data.copy()");
	}

	@Test public void finalLifecycleBindsOnceAndReleasesEachUiHostAfterSnapshot() throws Exception {
		containsAll(method("onViewCreated"), "attachmentController.attachView(", "captchaController.attachView(createCaptchaHost())",
				"bindControllers()");
		String bind = method("bindControllers");
		containsAll(bind, "sendCoordinator.bind(createSendHost(), requireActivity(), getChanName(), getBoardName(), getThreadNumber())",
				"captchaController.bindState(this)", "attachmentController.bindImportState(this)");
		String destroy = method("onDestroyView");
		for (String release : new String[] {"sendCoordinator.unbind()", "captchaController.detachView()", "attachmentController.detachView()"})
			assertEquals("One release per view: " + release, 1, destroy.split(Pattern.quote(release), -1).length - 1);
		assertTrue(destroy.indexOf("saveDraftIfNeeded()") < destroy.indexOf("captchaController.detachView()"));
		assertTrue(destroy.indexOf("saveDraftIfNeeded()") < destroy.indexOf("attachmentController.detachView()"));
		containsAll(captcha("detachView"), "host = null");
		containsAll(attachment("detachView"), "host = null", "attachments.clear()", "videoThumbnailTasks.clear()",
				"attachmentContainer = null", "scrollView = null", "sendButton = null", "attachmentImportViewModel = null");
		containsAll(coordinator("unbind"), "binding = null", "postingConnection = null", "postingCallback = null", "host = null", "bindingGeneration++");
		containsAll(coordinator("isCurrentBinding"), "bound && binding != null && host != null && generation == bindingGeneration");
		assertFalse(destroy.contains("cancelSendPost("));
		assertFalse(source().contains("private void refreshCaptcha("));
		containsAll(method("onRefreshCaptcha"), "captchaController.refreshCaptcha(this, forceRefresh, false, true)");
		containsAll(method("createSendHost"), "captchaController.refreshCaptcha(PostingFragment.this, false, mayShowLoadButton, true)");
	}

}
