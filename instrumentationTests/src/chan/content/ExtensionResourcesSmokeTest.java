package chan.content;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.LocaleList;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Isolated resource owners: does not update installed extensions, network state or user preferences. */
@RunWith(AndroidJUnit4.class)
public class ExtensionResourcesSmokeTest {
	private static Context context() {
		return InstrumentationRegistry.getInstrumentation().getTargetContext();
	}

	private static Configuration configuration(Locale locale) {
		Configuration configuration = new Configuration(context().getResources().getConfiguration());
		configuration.setLocales(new LocaleList(locale));
		return configuration;
	}

	private static Chan isolatedChan(int iconResId) throws Exception {
		Chan.Provider provider = new Chan.Provider(null);
		ChanResources resources = new ChanResources(context(), configuration(Locale.US));
		ChanConfiguration configuration = ChanConfiguration.INITIALIZER.initialize(
				ChanConfiguration.class.getClassLoader(), ChanConfiguration.class.getName(),
				"resource-smoke", provider, resources);
		Chan chan = new Chan("resource-smoke", context().getPackageName(), configuration, null, null, null, iconResId);
		provider.set(chan);
		return chan;
	}

	@Test public void configurationChangeReplacesResourcesAndPublishesNewGeneration() {
		ChanResources owner = new ChanResources(context(), configuration(Locale.US));
		ChanResources.Snapshot before = owner.getSnapshot();
		assertTrue(owner.update(configuration(Locale.forLanguageTag("ru"))));
		ChanResources.Snapshot after = owner.getSnapshot();
		assertNotSame(before, after);
		assertNotSame(before.resources, after.resources);
		assertTrue(after.generation > before.generation);
		assertEquals("ru", after.resources.getConfiguration().getLocales().get(0).getLanguage());
		assertEquals("en", before.resources.getConfiguration().getLocales().get(0).getLanguage());
	}

	@Test public void unchangedConfigurationKeepsGenerationAndNewOwnerNeverReusesIt() {
		Configuration configuration = configuration(Locale.US);
		ChanResources owner = new ChanResources(context(), configuration);
		long before = owner.getSnapshot().generation;
		assertFalse(owner.update(new Configuration(configuration)));
		assertEquals(before, owner.getSnapshot().generation);
		assertTrue(new ChanResources(context(), configuration).getSnapshot().generation > before);
	}

	@Test public void densityAndNightChangesReplaceResourcesEvenWithSameLocale() {
		Configuration original = configuration(Locale.US);
		ChanResources owner = new ChanResources(context(), original);
		long generation = owner.getSnapshot().generation;
		Configuration changed = new Configuration(original);
		changed.densityDpi += 40;
		changed.uiMode = (changed.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) |
				((original.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
						? Configuration.UI_MODE_NIGHT_NO : Configuration.UI_MODE_NIGHT_YES);
		assertTrue(owner.update(changed));
		assertTrue(owner.getSnapshot().generation > generation);
		Configuration actual = owner.getSnapshot().resources.getConfiguration();
		assertEquals(changed.densityDpi, actual.densityDpi);
		assertEquals(changed.uiMode & Configuration.UI_MODE_NIGHT_MASK,
				actual.uiMode & Configuration.UI_MODE_NIGHT_MASK);
		assertEquals("en", actual.getLocales().get(0).getLanguage());
	}

	@Test public void newAndLegacyUrisReadSameResourceAndIgnoreIncomingGeneration() throws Exception {
		Chan chan = isolatedChan(0);
		Uri uri = chan.configuration.getResourceUri(R.drawable.ic_extension);
		assertNotNull(uri);
		assertEquals("chan", uri.getScheme());
		assertEquals(chan.name, uri.getAuthority());
		assertEquals(String.valueOf(chan.configuration.getResourcesGenerationInternal()), uri.getQueryParameter("g"));
		Uri legacy = new Uri.Builder().scheme("chan").authority("").path(uri.getPath()).build();
		ByteArrayOutputStream modernBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream legacyBytes = new ByteArrayOutputStream();
		assertTrue(chan.configuration.readResourceUri(uri, modernBytes));
		assertTrue(chan.configuration.readResourceUri(legacy, legacyBytes));
		assertTrue(modernBytes.size() > 0);
		assertArrayEquals(modernBytes.toByteArray(), legacyBytes.toByteArray());
		assertTrue(chan.configuration.readResourceUri(uri.buildUpon().clearQuery()
				.appendQueryParameter("g", "not-a-generation").build(), new ByteArrayOutputStream()));
		assertFalse(chan.configuration.readResourceUri(uri.buildUpon().authority("other-chan").build(),
				new ByteArrayOutputStream()));
	}

	@Test public void cachedUrisAreRegeneratedButOldUrisStayReadable() throws Exception {
		Chan chan = isolatedChan(0);
		Uri before = chan.configuration.getResourceUri(R.drawable.ic_extension);
		chan.configuration.updateResources(configuration(Locale.forLanguageTag("ru")));
		Uri after = chan.configuration.getResourceUri(R.drawable.ic_extension);
		assertNotEquals(before, after);
		assertNotEquals(before.getQueryParameter("g"), after.getQueryParameter("g"));
		assertTrue(chan.configuration.readResourceUri(before, new ByteArrayOutputStream()));
	}

	@Test public void invalidForeignAndUnstreamableResourcesDoNotThrow() throws Exception {
		Chan chan = isolatedChan(0);
		assertNull(chan.configuration.getResourceUri(0));
		assertNull(chan.configuration.getResourceUri(0x7fffffff));
		assertNull(chan.configuration.getResourceUri(android.R.drawable.ic_delete));
		for (String value : new String[] {"chan://resource-smoke/res/drawable/does_not_exist",
				"chan://resource-smoke/res/drawable/android%3Adrawable%2Fic_delete",
				"chan://resource-smoke/res/drawable", "chan:opaque", "https://resource-smoke/res/drawable/icon"}) {
			assertFalse(chan.configuration.readResourceUri(Uri.parse(value), new ByteArrayOutputStream()));
		}
		assertFalse(chan.configuration.readResourceUri(null, new ByteArrayOutputStream()));
		Uri scalar = chan.configuration.getResourceUri(R.string.details);
		assertNotNull(scalar);
		assertFalse(chan.configuration.readResourceUri(scalar, new ByteArrayOutputStream()));
	}

	@Test public void genuineOutputIoFailureStillPropagates() throws Exception {
		Chan chan = isolatedChan(0);
		try {
			chan.configuration.readResourceUri(chan.configuration.getResourceUri(R.drawable.ic_extension),
					new OutputStream() {
						@Override public void write(int value) throws IOException {
							throw new IOException("Expected smoke-test output failure");
						}
					});
			fail("I/O errors must not be hidden as an invalid resource");
		} catch (IOException expected) {
			assertEquals("Expected smoke-test output failure", expected.getMessage());
		}
	}

	@Test public void extensionAndFallbackIconsResolveWithoutCrashing() throws Exception {
		ChanManager manager = ChanManager.getInstance();
		assertNotNull(manager.getIcon(isolatedChan(R.drawable.ic_extension)));
		assertNotNull(manager.getIcon(isolatedChan(0x7fffffff)));
		assertNotNull(manager.getIcon(isolatedChan(0)));
		assertNotNull(manager.getIcon(Chan.get((String) null)));
	}

	@Test public void packageContextResourcesCanBeConfiguredWithoutIncludingCode() throws Exception {
		Context packageContext = context().createPackageContext(context().getPackageName(), 0);
		ChanResources owner = new ChanResources(packageContext, configuration(Locale.forLanguageTag("ru")));
		assertEquals("ru", owner.getSnapshot().resources.getConfiguration().getLocales().get(0).getLanguage());
		assertEquals(context().getPackageName(), owner.getSnapshot().resources.getResourcePackageName(R.drawable.ic_extension));
	}
}
