package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.ui.posting.PostingFormDiagnostics;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;

/** Disposable view contents and a separate export directory, never real drafts or identity. */
public class PostingFormDiagnosticsTest {
	@Test public void geometryExportOmitsFieldValuesAndFilenameAndReleasesObserver() throws Exception {
		Assume.assumeFalse(PostingFormDiagnostics.isRecording() || PostingFormDiagnostics.isSaving());
		CountDownLatch saved = new CountDownLatch(1);
		File[] export = new File[1], directory = new File[1];
		String[] canaries = {"PRIVATE_COMMENT_CANARY", "PRIVATE_NAME_CANARY", "PRIVATE_EMAIL_CANARY",
				"PRIVATE_PASSWORD_CANARY", "PRIVATE_CAPTCHA_CANARY", "PRIVATE_FILENAME_CANARY.jpg"};
		try (ActivityScenario<PostingGestureTestActivity> scenario = ActivityScenario.launch(PostingGestureTestActivity.class)) {
			try {
				scenario.onActivity(activity -> {
					directory[0] = new File(activity.getCacheDir(), "posting-diagnostics-test-" + UUID.randomUUID());
					assertTrue(directory[0].mkdir());
					Context exportContext = new ContextWrapper(activity) {
						@Override public Context getApplicationContext() { return this; }
						@Override public File getCacheDir() { return directory[0]; }
					};
					Context context = PostingTestContext.create(activity);
					View root = LayoutInflater.from(context).inflate(R.layout.activity_posting, null);
					LayoutInflater.from(context).inflate(R.layout.activity_posting_footer_common,
							(FrameLayout) root.findViewById(R.id.footer_container));
					int[] ids = {R.id.comment, R.id.name, R.id.email, R.id.password, R.id.captcha_input};
					for (int i = 0; i < ids.length; i++) ((EditText) root.findViewById(ids[i])).setText(canaries[i]);
					TextView filename = new TextView(context); filename.setText(canaries[5]);
					((LinearLayout) root.findViewById(R.id.attachment_container)).addView(filename);
					activity.setContentView(root);
					PostingFormDiagnostics.start(exportContext);
					android.app.AlertDialog fixture = MotionDialogBuilder.standard(context).setTitle(canaries[0])
							.setMessage(canaries[1]).setPositiveButton(android.R.string.ok, null).create();
					android.graphics.drawable.Drawable original = fixture.getWindow().getDecorView().getBackground();
					int originalWidth = fixture.getWindow().getAttributes().width;
					com.mishiranu.dashchan.util.ScreenDiagnostics.observe(fixture.getWindow(), "fixture");
					assertSame(original, fixture.getWindow().getDecorView().getBackground());
					assertEquals(originalWidth, fixture.getWindow().getAttributes().width);
					fixture.show(); fixture.dismiss();
					PostingFormDiagnostics.Observer observer = new PostingFormDiagnostics.Observer(activity, root,
							root.findViewById(R.id.scroll_view), root.findViewById(R.id.comment), root.findViewById(R.id.footer_container));
					PostingFormDiagnostics.trace(root.findViewById(R.id.comment), "fixture_event", "height=123");
					observer.close();
					PostingFormDiagnostics.trace(root, "MUST_NOT_APPEAR_AFTER_CLOSE", "height=456");
					PostingFormDiagnostics.stopAndSave(exportContext, file -> { export[0] = file; saved.countDown(); });
				});
				assertTrue(saved.await(5, TimeUnit.SECONDS)); assertNotNull(export[0]);
				String report = new String(Files.readAllBytes(export[0].toPath()), StandardCharsets.UTF_8);
				assertTrue(report.contains("diagnostics v5")); assertTrue(report.contains("geometry editor{"));
				assertTrue(report.contains("screen window=")); assertTrue(report.contains("standardRequested=true"));
				assertFalse(report.contains("snapshotUnavailable="));
				assertTrue(report.contains("attachment_tree")); assertTrue(report.contains("fixture_event height=123"));
				assertFalse(report.contains("MUST_NOT_APPEAR_AFTER_CLOSE"));
				for (String canary : canaries) assertFalse(report.contains(canary));
			} finally {
				if (export[0] != null) export[0].delete();
				if (directory[0] != null) directory[0].delete();
			}
		}
	}
}
