package com.mishiranu.dashchan;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.content.async.ReadChangelogTask;
import com.mishiranu.dashchan.content.async.ReadCaptchaTask;
import com.mishiranu.dashchan.content.async.TaskViewModel;
import com.mishiranu.dashchan.ui.posting.PostingCaptchaController;
import com.mishiranu.dashchan.ui.preference.TextFragment;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercise default-constructor reflection and the real generic callback factory. */
@RunWith(AndroidJUnit4.class)
public class ReflectionContractSmokeTest {
	@Test public void captchaViewModelKeepsPublicConstructorAndCallbackSignature() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			try {
				PostingCaptchaController.CaptchaViewModel model = PostingCaptchaController.CaptchaViewModel.class
						.getDeclaredConstructor().newInstance();
				assertEquals(TaskViewModel.Proxy.class, model.getClass().getSuperclass());
				ParameterizedType type = (ParameterizedType) model.getClass().getGenericSuperclass();
				assertEquals(ReadCaptchaTask.Callback.class, type.getActualTypeArguments()[1]);
				assertTrue(Proxy.isProxyClass(model.callback.getClass()));
				assertTrue(model.callback instanceof ReadCaptchaTask.Callback);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	@Test public void callbackFactoryRetainsTheRuntimeGenericContract() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			TextFragment.ChangelogViewModel model;
			try {
				model = TextFragment.ChangelogViewModel.class.getDeclaredConstructor().newInstance();
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
			assertEquals(TaskViewModel.Proxy.class, model.getClass().getSuperclass());
			ParameterizedType type = (ParameterizedType) model.getClass().getGenericSuperclass();
			assertEquals(ReadChangelogTask.Callback.class, type.getActualTypeArguments()[1]);
			assertTrue(Proxy.isProxyClass(model.callback.getClass()));
			assertTrue(model.callback instanceof ReadChangelogTask.Callback);
		});
	}
}
