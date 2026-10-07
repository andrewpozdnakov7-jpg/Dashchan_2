#!/usr/bin/env python3
"""Posting source contracts only; does not compile or run JUnit/Android code."""
import re
import runpy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FRAGMENT = ROOT / "src/com/mishiranu/dashchan/ui/posting/PostingFragment.java"
CONTROLLER = ROOT / "src/com/mishiranu/dashchan/ui/posting/PostingAttachmentsController.java"
JVM = ROOT / "unitTests/src/com/mishiranu/dashchan/ui/posting/PostingFlowContractTest.java"
ANDROID = ROOT / "instrumentationTests/src/com/mishiranu/dashchan/PostingDraftContractTest.java"
ANDROID_CONTROLLER = ROOT / "instrumentationTests/src/com/mishiranu/dashchan/ui/posting/PostingAttachmentsControllerTest.java"
CAPTCHA = ROOT / "src/com/mishiranu/dashchan/ui/posting/PostingCaptchaController.java"
DRAFT = ROOT / "src/com/mishiranu/dashchan/ui/posting/PostingDraftController.java"
STATE_TESTS = ROOT / "instrumentationTests/src/com/mishiranu/dashchan/ui/posting/PostingStateControllersTest.java"
SEND = ROOT / "src/com/mishiranu/dashchan/ui/posting/PostingSendCoordinator.java"
FORM = ROOT / "src/com/mishiranu/dashchan/ui/posting/PostingFormData.java"
SEND_TESTS = ROOT / "instrumentationTests/src/com/mishiranu/dashchan/ui/posting/PostingSendCoordinatorTest.java"


def method(source, name):
    # Strip comments and quoted literals for structural brace matching only.
    masked = re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                    lambda m: " " * len(m[0]), source, flags=re.S)
    matches = re.finditer(r"(?m)^\t(?:@Override )?(?:private |public |protected )?(?:static )?[\w<>.]+ " + re.escape(name) + r"\(", masked)
    start = None
    for match in matches:
        brace = masked.find("{", match.end())
        # Skip interface declarations instead of matching an unrelated later body.
        if brace >= 0 and ";" not in masked[match.end():brace]:
            start = brace + 1
            break
    assert start is not None, f"Missing method body {name}"
    depth, end = 1, start
    while depth:
        assert end < len(masked), f"Unclosed method {name}"
        depth += (masked[end] == "{") - (masked[end] == "}")
        end += 1
    return re.sub(r"\s+", " ", source[start:end - 1])


def require(body, *fragments):
    for fragment in fragments:
        assert fragment in body, f"Missing posting boundary: {fragment}"


def main():
    balance = runpy.run_path(str(ROOT / "unitTests/check_photo_editor_sources.py"))["lexical_balance"]
    for path in (FRAGMENT, CONTROLLER, CAPTCHA, DRAFT, SEND, FORM, JVM, ANDROID, ANDROID_CONTROLLER, STATE_TESTS, SEND_TESTS):
        balance(path)
    source = FRAGMENT.read_text(encoding="utf-8")
    body = lambda name: method(source, name)
    controller = CONTROLLER.read_text(encoding="utf-8")
    attachment = lambda name: method(controller, name)
    captcha_source = CAPTCHA.read_text(encoding="utf-8")
    draft_source = DRAFT.read_text(encoding="utf-8")
    captcha = lambda name: method(captcha_source, name)
    draft = lambda name: method(draft_source, name)
    send_source = SEND.read_text(encoding="utf-8")
    form_source = FORM.read_text(encoding="utf-8")
    coordinator = lambda name: method(send_source, name)
    form = lambda name: method(form_source, name)
    picker = source.index("attachmentPicker = registerForActivityResult(")
    editor = source.index("imageEditor = registerForActivityResult(")
    constructor = source.index("public PostingFragment()")
    assert picker < editor < constructor and source.count("registerForActivityResult(") == 2
    require(source[picker:editor], "C.REQUEST_CODE_ATTACH", "result.getResultCode()", "result.getData()")
    require(source[editor:constructor], "C.REQUEST_CODE_IMAGE_EDITOR", "result.getResultCode()", "result.getData()")
    require(body("capturePostDraft"), "attachmentController.createAttachmentDrafts()", "commentView.getSelectionEnd()",
            "getChanName(), getBoardName(), getThreadNumber(), name, email, password",
            "subject, comment, commentCarriage, attachmentDrafts", "optionSage, optionSpoiler, optionOriginalPoster, userIcon")
    require(attachment("createAttachmentDrafts"), "for (AttachmentHolder holder : attachments)",
            "holder.hash, holder.name, holder.rating", "holder.optionUniqueHash, holder.optionRemoveMetadata, holder.optionRemoveFileName",
            "holder.optionSpoiler, holder.reencoding")
    assert "getTextIfVisible" not in body("capturePostDraft")
    require(draft("restorePostDraft"), "store.isPostDraftQueued(draft)", "store.restoreFailedPostDraft(")
    require(body("onViewCreated"), "draftController.restorePostDraft(",
            "attachmentController.restoreAttachmentDrafts(postDraft.attachmentDrafts)")
    require(attachment("restoreAttachmentDrafts"), "attachmentDraft.hash, attachmentDraft.name, attachmentDraft.rating",
            "attachmentDraft.optionUniqueHash, attachmentDraft.optionRemoveMetadata",
            "attachmentDraft.optionRemoveFileName, attachmentDraft.optionSpoiler", "attachmentDraft.reencoding")
    require(body("captureCaptchaDraft"), "captchaController.createDraft(captchaForm.getInput())")
    require(captcha("createDraft"), "captchaType, captchaState, captchaData, loadedCaptchaType",
            "loadedCaptchaInput, loadedCaptchaValidity, input, captchaImage, captchaLarge",
            "captchaBlackAndWhite, captchaLoadTime, boardName, threadNumber")
    require(body("onSaveInstanceState"), "outState.putParcelable(EXTRA_CAPTCHA_DRAFT, captchaDraft)", "saveDraftIfNeeded()")
    require(body("saveDraftIfNeeded"), "draftController.saveDraft(sendCoordinator.isSendSuccess(), getChanName(), this::capturePostDraft, this::captureCaptchaDraft)")
    require(draft("saveDraft"), "if (!sendSuccess && !draftSaved)", "draftSaved = true",
            "store.storePost(post.get())", "store.storeCaptcha(chan, captcha.get())")
    destroy = body("onDestroyView")
    require(destroy, "formDiagnostics.close()", "sendCoordinator.unbind()", "saveDraftIfNeeded()", "attachmentController.detachView()",
            "removeCallbacks(resizeComment)", "removeOnPreDrawListener(showCommentAfterLayout)")
    detach = attachment("detachView")
    require(detach, "clearAttachmentDragState()", "task.cancel()", "videoThumbnailTasks.clear()",
            "attachments.clear()", "host = null", "scrollView = null", "attachmentContainer = null",
            "sendButton = null", "attachmentImportViewModel = null", "processingNotice = null",
            "removeCallbacks(processingNotice)", "setOnDragListener(null)")
    for field in ("formDiagnostics scrollView commentView sageCheckBox spoilerCheckBox originalPosterCheckBox checkBoxParent "
                  "nameView emailView passwordView subjectView iconView personalDataBlock textFormatView "
                  "commentEditWatcher captchaForm sendButton").split():
        require(destroy, field + " = null")
    assert destroy.index("saveDraftIfNeeded()") < destroy.index("commentView = null") and "cancelSendPost(" not in destroy
    require(captcha("bindState"), "viewModel.observe(owner.getViewLifecycleOwner(), this)")
    require(destroy, "captchaController.detachView()")
    require(captcha("detachView"), "host = null")
    assert destroy.index("saveDraftIfNeeded()") < destroy.index("captchaController.detachView()")
    require(body("bindControllers"), "captchaController.bindState(this)",
            "attachmentController.bindImportState(this)")
    require(attachment("bindImportState"), "new ViewModelProvider(owner)",
            "attachmentImportViewModel.hasTaskOrValue()",
            "attachmentImportViewModel.observe(owner.getViewLifecycleOwner(), this::onAttachmentImportComplete)")
    require(body("onResume"), "consumeFuturePostText()", "getFutureAttachmentDrafts()", "consumeFutureAttachmentDrafts()",
            "sendCoordinator.deliverPendingUiEvents()", "draftController.resetSavedGuard()", "if (!allowPosting || sendCoordinator.isSendSuccess())")
    require(coordinator("createCallback"), "sendSuccess = true", "if (host.isResumed()) host.closePostingFragment()", "dismiss()")
    move = attachment("moveAttachment")
    require(move, "attachments.indexOf(holder)", "sourceIndex < 0 || sourceIndex == targetIndex",
            "attachments.remove(sourceIndex)", "attachments.add(targetIndex, holder)", "invalidateAttachments(true)", "host.saveDraftAfterAttachmentChange()")
    assert move.index("attachments.remove(sourceIndex)") < move.index("attachments.add(targetIndex, holder)")
    assert "new AttachmentHolder" not in move
    remove = controller[controller.index("private final View.OnClickListener attachmentRemoveListener"):
                    controller.index("private void invalidateAttachments")]
    require(remove, "(AttachmentHolder) v.getTag()", "attachments.remove(holder)", "videoThumbnailTasks.remove(holder)",
            "task.cancel()", "host.saveDraftAfterAttachmentChange()")
    require(body("getTextIfVisible"), "editText.getVisibility() == View.VISIBLE", "StringUtils.nullIfEmpty")
    require(body("isCheckedIfVisible"), "checkBox.getVisibility() == View.VISIBLE && checkBox.isChecked()")
    send = body("submitPostingForm")
    collect = body("collectPostingFormData")
    for field in "subjectView commentView nameView emailView passwordView".split():
        require(collect, "getTextIfVisible(" + field + ")")
    for field in "sageCheckBox spoilerCheckBox originalPosterCheckBox".split():
        require(collect, "isCheckedIfVisible(" + field + ")")
    require(send, "if (attachmentController.isImportInProgress())", "sendCoordinator.canSend()",
            "sendCoordinator.send(collectPostingFormData(), this::capturePostDraft, this::captureCaptchaDraft)")
    require(collect, "PostingFormData.resolvePassword(getTextIfVisible(passwordView)",
            "Preferences.getPassword(Chan.get(getChanName()))", "iconView.getVisibility() == View.VISIBLE ? getUserIcon() : null",
            "attachmentController.createSendAttachments()", "captchaController.snapshot(captchaForm.getInput())",
            "new PostingFormData(subject, comment, name, email, password, optionSage, optionSpoiler, optionOriginalPoster, userIcon, sendAttachments, captcha)")
    require(form("createRequest"), "new ChanPerformer.SendPostData(boardName, threadNumber, subject, comment, name, email, password",
            "sage, spoiler, originalPoster, userIcon, captcha.type", "captcha.data.copy()", "captcha.needLoad, 15000, 45000")
    require(form("resolvePassword"), "password != null ? password : fallback.get()")
    require(form("allowFloodRetry"), "captcha.state == ReadCaptchaTask.CaptchaState.PASS")
    require(coordinator("send"), "form.createRequest(boardName, threadNumber)", "drafts.storeForSend(draft, chanName, captcha)",
            "allowDialog = false", "postingBinder.send(", "form.createAttachmentHashes()", "form.allowFloodRetry()",
            "host.setSendButtonEnabled(false)", "host.dismissPostingProgress()", "minimize()", "drafts.resetSavedGuard()")
    require(captcha("snapshot"), "captchaData.copy()", "data.put(ChanPerformer.CaptchaData.INPUT, userInput)",
            "loadedCaptchaType != null ? loadedCaptchaType : captchaType")
    require(captcha_source, "ReadCaptchaTask.CaptchaState.MAY_LOAD", "ReadCaptchaTask.CaptchaState.MAY_LOAD_SOLVING")
    require(draft("storeForSend"), "store.storePost(postDraft)", "store.storeCaptcha(chan, captcha.get())", "draftSaved = true")
    require(attachment("createSendAttachments"), "for (int i = 0; i < attachments.size(); i++)",
            "AttachmentHolder data = attachments.get(i)", "if (!found)", "rating = null",
            "attachmentRatingItems.get(0).first", "if (fileHolder != null)", "attachmentHashes.add(data.hash)",
            "fileHolder, data.name, rating", "data.optionUniqueHash, data.optionRemoveMetadata, data.optionRemoveFileName",
            "postingConfiguration.attachmentSpoiler && data.optionSpoiler, data.reencoding",
            "return new SendAttachments(attachments, attachmentHashes)")
    assert coordinator("send").index("drafts.storeForSend(") < coordinator("send").index("postingBinder.send(")
    assert captcha("snapshot").index("captchaData.copy()") < captcha("snapshot").index("data.put(")
    require(coordinator("cancel"), "host.forgetPostingProgress()", "postingBinder.cancel(chanName, boardName, threadNumber)")
    require(coordinator("minimize"), "host.closePostingFragment()")
    assert "cancel(" not in coordinator("minimize") and "cancel(" not in coordinator("unbind")
    require(body("handleFailResult"), "sendCoordinator.handleFailResult(failResult)")
    require(coordinator("handleFailResult"), "host.isResumed()", "failResult = failure", "httpResponseCode == 0 && !failure.keepCaptcha",
            "host.refreshCaptcha(!failure.captchaError)", "host.updatePostingConfigurationIfNeeded()")
    require(coordinator("deliverPendingUiEvents"), "failResult = null", "handleFailResult(pending)")
    require(coordinator("unbind"), "releaseEndpoint()", "binding = null", "postingConnection = null",
            "postingCallback = null", "bindingGeneration++", "previous.unbind(connection)", "dismiss()", "host = null")
    require(send_source, "Context.BIND_AUTO_CREATE", "postingBinder.register(callback, chanName, boardName, threadNumber)",
            "postingBinder.unregister(postingCallback)", "generation == bindingGeneration")
    require(body("bindControllers"), "sendCoordinator.bind(createSendHost(), requireActivity(), getChanName(), getBoardName(), getThreadNumber())")
    assert destroy.index("sendCoordinator.unbind()") < destroy.index("saveDraftIfNeeded()")
    for field in ("postingBinder", "postingConnection", "postingCallback"):
        assert field not in source, field
    for field in ("sendSuccess", "failResult", "allowDialog"):
        assert not re.search(r"(?m)^\tprivate (?:final )?[\w.<>]+ " + field + r"[; =]", source), field
    for forbidden in ("import android.view.", "import android.widget.", "private PostingFragment", "PostingFragment.this", "private Fragment", "private View"):
        assert forbidden not in send_source, forbidden
    assert "sendCoordinator.cancel()" in body("createSendHost") and "sendCoordinator.minimize()" in body("createSendHost")
    assert "progressMax / 1000" in body("createSendHost")
    assert "new ChanPerformer.SendPostData" not in source
    require(form_source, "Collections.unmodifiableList", "new ArrayList<>(attachments.hashes)",
            "new PostingCaptchaController.CaptchaSnapshot", "captcha.data.copy()")
    require(coordinator("unbind"), "if (previous != null) previous.unbind(connection)")
    assert "android.view" not in form_source and "android.widget" not in form_source
    assert SEND_TESTS.read_text(encoding="utf-8").count("@Test ") == 12
    require(body("handlePostingActivityResult"), "attachmentController.handleActivityResult(requestCode, resultCode, data)")
    result = attachment("handleActivityResult")
    require(result, "resultCode == Activity.RESULT_OK && data != null", "case C.REQUEST_CODE_ATTACH",
            "Intent.FLAG_GRANT_READ_URI_PERMISSION", "LinkedHashSet<Uri> uris", "uris.add(dataUri)", "uris.add(uri)",
            "case C.REQUEST_CODE_IMAGE_EDITOR", "AttachmentResultGuard.matches(", "EXTRA_RESULT_SOURCE_HASH",
            "EXTRA_RESULT_SOURCE_NAME", "holder.hash, holder.name", "holder.hash = hash", "holder.name = name")
    assert result.index("AttachmentResultGuard.matches(") < result.index("holder.hash = hash")
    for field in ("ArrayList<AttachmentHolder> attachments", "videoThumbnailTasks", "draggedAttachment",
                  "attachmentDragTarget", "attachmentColumnCount", "attachmentImportInProgress",
                  "attachmentImportViewModel"):
        assert field not in source, f"Duplicate attachment state: {field}"
    assert "PostingService" not in controller and "CaptchaForm" not in controller
    host = controller[controller.index("interface Host"):controller.index("private Host host")]
    assert host.count(");") == 11
    require(attachment("onAttachmentImportComplete"), "setAttachmentImportInProgress(false)",
            "result.requestedCount - attachedCount", "attachments.size() < postingConfiguration.attachmentCount",
            "host.saveDraftAfterAttachmentChange()")
    require(attachment("setAttachmentImportInProgress"), "removeCallbacks(processingNotice)",
            "sendButton == currentSendButton && attachmentImportInProgress", "500L")
    require(attachment("openSystemAttachmentPicker"), "Intent.ACTION_GET_CONTENT, null")
    require(attachment("moveAttachment"), "host.saveDraftAfterAttachmentChange()")
    require(re.sub(r"\s+", " ", controller), 'newSingleThreadPool(3000, "PostingThumbnail", null)',
            "oldTask.cancel()", "boolean currentTask = videoThumbnailTasks.get(holder) == this",
            "bitmap != null && currentTask && host != null && attachments.contains(holder) && hash.equals(holder.hash)",
            "bitmap.recycle()", "finally { releasePermissions(); }")
    for field in ("captchaType captchaState captchaData loadedCaptchaType loadedCaptchaInput "
                  "loadedCaptchaValidity captchaImage captchaLarge captchaBlackAndWhite captchaLoadTime draftSaved").split():
        assert not re.search(r"(?m)^\tprivate (?:final )?[\w.<>]+ " + field + r"[; =]", source), field
    for forbidden in ("import android.view.", "import android.widget.", "CaptchaForm", "AttachmentHolder",
                      "PostingAttachmentsController", "PostingCaptchaController", "private Fragment", "private Host"):
        assert forbidden not in draft_source, forbidden
    assert "private Fragment" not in captcha_source and "private View" not in captcha_source
    assert "PostingDraftController" not in captcha_source and "PostingAttachmentsController" not in captcha_source
    require(body("onViewCreated"), "captchaController.attachView(createCaptchaHost())",
            "captchaController.restoreDraft(captchaDraft, savedCaptcha, captcha)")
    require(captcha("refreshCaptcha"), "if (host == null) return", "!forceCaptcha || captchaState != ReadCaptchaTask.CaptchaState.MAY_LOAD_SOLVING",
            "restart || !viewModel.hasTaskOrValue()", "forceCaptcha ? null : Preferences.getCaptchaPass(chan)",
            "task.execute(ConcurrentUtils.PARALLEL_EXECUTOR)", "viewModel.attach(task)")
    require(captcha("onReadCaptchaSuccess"), "if (host == null) return", "clock.getAsLong()", "host.updatePostingConfigurationIfNeeded()")
    require(captcha("onReadCaptchaError"), "if (host == null) return", "host.showError(errorItem)")
    require(captcha("canRestoreStoredDraft"), "validity.compareTo(draft.loadedValidity) >= 0",
            "case SHORT_LIFETIME: return false", "case IN_THREAD:", "case IN_BOARD_SEPARATELY:",
            "case IN_BOARD:", "case LONG_LIFETIME: return true")
    require(body("compareListOfPairs"), "first.get(i).first, second.get(i).first",
            "first.get(i).second, second.get(i).second")
    assert body("compareListOfPairs").strip().endswith("return true;")
    # Final-stage boundaries: snapshot before detach, no thin refresh wrapper or old UI send path.
    require(body("onViewCreated"), "attachmentController.attachView(", "captchaController.attachView(createCaptchaHost())", "bindControllers()")
    for release in ("sendCoordinator.unbind()", "captchaController.detachView()", "attachmentController.detachView()"):
        assert destroy.count(release) == 1, release
    assert destroy.index("saveDraftIfNeeded()") < destroy.index("attachmentController.detachView()")
    assert "private void refreshCaptcha(" not in source and "private void executeSendPost(" not in source
    require(body("onRefreshCaptcha"), "captchaController.refreshCaptcha(this, forceRefresh, false, true)")
    require(body("createSendHost"), "captchaController.refreshCaptcha(PostingFragment.this, false, mayShowLoadButton, true)")
    require(coordinator("isCurrentBinding"), "bound && binding != null && host != null && generation == bindingGeneration")
    assert "PostingAttachmentsController" not in send_source and "PostingCaptchaController" not in send_source
    assert "PostingSendCoordinator" not in draft_source and "PostingSendCoordinator" not in captcha_source
    assert STATE_TESTS.read_text(encoding="utf-8").count("@Test ") == 12
    assert JVM.read_text(encoding="utf-8").count("@Test ") == 12
    assert ANDROID.read_text(encoding="utf-8").count("@Test ") == 8
    assert ANDROID_CONTROLLER.read_text(encoding="utf-8").count("@Test ") == 8
    print("PASS: posting source boundaries (drafts, ordered attachments, captcha, send, lifecycle, activity results)")
    print("PASS: lexical balance; prepared 12 JVM source contracts, 8 Android model round trips, 8 Android attachment, 12 captcha/draft and 12 send-controller tests")
    print("NOT RUN: Java compilation, JUnit, Android instrumentation, Gradle build, lint, device smoke")


if __name__ == "__main__":
    main()
