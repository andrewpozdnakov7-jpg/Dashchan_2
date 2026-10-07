package chan.content;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.LocaleList;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import chan.text.JsonSerial;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.content.CacheManager;
import com.mishiranu.dashchan.content.model.Post;
import com.mishiranu.dashchan.content.model.PostNumber;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
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
		assertNull(uri.getQuery());
		assertNull(uri.getFragment());
		Uri legacy = new Uri.Builder().scheme("chan").authority("").path(uri.getPath()).build();
		ByteArrayOutputStream modernBytes = new ByteArrayOutputStream();
		ByteArrayOutputStream legacyBytes = new ByteArrayOutputStream();
		assertTrue(chan.configuration.readResourceUri(uri, modernBytes));
		assertTrue(chan.configuration.readResourceUri(legacy, legacyBytes));
		assertTrue(modernBytes.size() > 0);
		assertArrayEquals(modernBytes.toByteArray(), legacyBytes.toByteArray());
		assertTrue(chan.configuration.readResourceUri(uri.buildUpon().clearQuery()
				.appendQueryParameter("g", "not-a-generation").build(), new ByteArrayOutputStream()));
		ByteArrayOutputStream intermediateBytes = new ByteArrayOutputStream();
		assertTrue(chan.configuration.readResourceUri(uri.buildUpon().appendQueryParameter("g", "123")
				.fragment("old").build(), intermediateBytes));
		assertArrayEquals(modernBytes.toByteArray(), intermediateBytes.toByteArray());
		assertFalse(chan.configuration.readResourceUri(uri.buildUpon().authority("other-chan").build(),
				new ByteArrayOutputStream()));
	}

	@Test public void cachedUrisStayStableAcrossConfigurationAndReplacementOwners() throws Exception {
		Chan chan = isolatedChan(0);
		Uri before = chan.configuration.getResourceUri(R.drawable.ic_extension);
		long generation = chan.configuration.getResourcesGenerationInternal();
		chan.configuration.updateResources(configuration(Locale.forLanguageTag("ru")));
		Uri after = chan.configuration.getResourceUri(R.drawable.ic_extension);
		assertEquals(before, after);
		assertTrue(chan.configuration.getResourcesGenerationInternal() > generation);
		Chan replacement = isolatedChan(0);
		assertEquals(before, replacement.configuration.getResourceUri(R.drawable.ic_extension));
		assertTrue(replacement.configuration.getResourcesGenerationInternal()
				> chan.configuration.getResourcesGenerationInternal());
		assertTrue(chan.configuration.readResourceUri(before, new ByteArrayOutputStream()));
	}

	private static Post post(Uri icon) {
		Post.Builder builder = new Post.Builder();
		builder.number = new PostNumber(123, 0);
		builder.timestamp = 123L;
		builder.comment = "unchanged";
		builder.icons = Collections.singletonList(Post.Icon.createExternal(icon, "icon"));
		return builder.build(false);
	}

	private static byte[] serialized(Post post) throws IOException {
		try (JsonSerial.Writer writer = JsonSerial.writer()) {
			post.serialize(writer);
			return writer.build();
		}
	}

	@Test public void generationChangeDoesNotChangePostSerializationContentOrDatabaseHash() throws Exception {
		Chan chan = isolatedChan(0);
		Post before = post(chan.configuration.getResourceUri(R.drawable.ic_extension));
		chan.configuration.updateResources(configuration(Locale.forLanguageTag("ru")));
		Post after = post(chan.configuration.getResourceUri(R.drawable.ic_extension));
		assertTrue(before.isContentEqual(after));
		assertArrayEquals(serialized(before), serialized(after));
		MessageDigest hash = MessageDigest.getInstance("SHA-256");
		assertArrayEquals(hash.digest(serialized(before)), hash.digest(serialized(after)));
		Post intermediate = post(before.icons.get(0).uri.buildUpon().appendQueryParameter("g", "999")
				.fragment("old").build());
		assertTrue(before.isContentEqual(intermediate));
		assertArrayEquals(serialized(before), serialized(intermediate));
	}

	@Test public void cachedIntermediatePostNormalizesWithoutFalseEditedContentOrSchemaChange() throws Exception {
		Uri canonical = isolatedChan(0).configuration.getResourceUri(R.drawable.ic_extension);
		String json = "{\"flags\":0,\"timestamp\":123,\"comment\":\"unchanged\",\"icons\":[{\"uri\":\""
				+ canonical + "?g=123#old\",\"title\":\"icon\"}]}";
		Post old;
		try (JsonSerial.Reader reader = JsonSerial.reader(json.getBytes(StandardCharsets.UTF_8))) {
			old = Post.deserialize(new PostNumber(123, 0), false, reader);
		}
		assertEquals(canonical, old.icons.get(0).uri);
		// PagesDatabase already calls this comparison when the stored byte hash differs.
		assertTrue(post(canonical).isContentEqual(old));
		assertArrayEquals(serialized(post(canonical)), serialized(old));
	}

	@Test public void legacyCachedIconsCompareEqualOnlyWithKnownThreadOwner() throws Exception {
		Post fresh = post(Uri.parse("chan://dvach/res/raw/foo"));
		for (String legacy : new String[] {"chan:///res/raw/foo", "chan:///res/raw/foo?g=123#old"}) {
			String json = "{\"flags\":0,\"timestamp\":123,\"comment\":\"unchanged\",\"icons\":[{\"uri\":\""
					+ legacy + "\",\"title\":\"icon\"}]}";
			Post cached;
			try (JsonSerial.Reader reader = JsonSerial.reader(json.getBytes(StandardCharsets.UTF_8))) {
				cached = Post.deserialize(new PostNumber(123, 0), false, reader);
			}
			assertTrue(fresh.isContentEqual(cached, "dvach"));
			assertTrue(cached.isContentEqual(fresh, "dvach"));
			assertFalse(fresh.isContentEqual(cached));
			assertFalse(fresh.isContentEqual(cached, "fourchan"));
			assertNotEquals(serialized(fresh).length, 0);
			assertFalse(java.util.Arrays.equals(serialized(fresh), serialized(cached)));
			assertTrue(new String(serialized(fresh), StandardCharsets.UTF_8).contains("chan://dvach/res/raw/foo"));
			assertEquals(Uri.parse("chan:///res/raw/foo"), cached.icons.get(0).uri);
		}
	}

	@Test public void explicitOwnerAndRealPostChangesStillDiffer() throws Exception {
		Post fresh = post(Uri.parse("chan://dvach/res/raw/foo"));
		assertFalse(fresh.isContentEqual(post(Uri.parse("chan://fourchan/res/raw/foo")), "dvach"));
		assertFalse(fresh.isContentEqual(post(Uri.parse("chan://dvach/res/raw/bar")), "dvach"));
		Post.Builder changed = new Post.Builder();
		changed.number = fresh.number;
		changed.timestamp = fresh.timestamp;
		changed.comment = "actually edited";
		changed.icons = Collections.singletonList(Post.Icon.createExternal(Uri.parse("chan:///res/raw/foo"), "icon"));
		assertFalse(fresh.isContentEqual(changed.build(false), "dvach"));
	}

	@Test public void networkIconIdentityKeepsQueryAndFragment() throws Exception {
		Uri network = Uri.parse("https://example.org/a.png?g=1#part");
		assertEquals(network.toString(), ChanResourceAccess.normalizeResourceUriForContentComparison(network, "dvach"));
		assertTrue(post(network).isContentEqual(post(network), "dvach"));
		assertFalse(post(network).isContentEqual(post(Uri.parse("https://example.org/a.png?g=2#part")), "dvach"));
	}

	@Test public void canonicalArchiveIdentityAndChanCacheKeyIgnoreIntermediateMetadata() throws Exception {
		Uri canonical = isolatedChan(0).configuration.getResourceUri(R.drawable.ic_extension);
		CacheManager cache = CacheManager.getInstance();
		String expectedKey = cache.getCachedFileKey(canonical);
		for (String generation : new String[] {"1", "999", "not-a-generation"}) {
			Uri intermediate = canonical.buildUpon().appendQueryParameter("g", generation).fragment("old").build();
			assertEquals(canonical, ChanResourceAccess.canonicalize(intermediate));
			assertEquals(expectedKey, cache.getCachedFileKey(intermediate));
		}
		Uri network = Uri.parse("https://example.org/icon?revision=2#part");
		assertSame(network, ChanResourceAccess.canonicalize(network));
	}

	@Test public void longLivedReaderResolvesCurrentManagerChanInsteadOfStaleFallback() {
		Chan current = Chan.get("dvach");
		assertEquals("dvach", current.name);
		Chan stale = new Chan(current.name, current.packageName, current.configuration,
				current.performer, current.locator, current.markup, current.iconResId);
		assertNotSame(current, stale);
		assertSame(current, ChanResourceAccess.resolveResourceChan(stale, Uri.parse("chan://dvach/res/raw/foo?g=1")));
		assertSame(current, ChanResourceAccess.resolveResourceChan(stale, Uri.parse("chan:///res/raw/foo")));
		assertSame(current, ChanResourceAccess.resolveResourceChan(Chan.getFallback(), Uri.parse("chan://dvach/res/raw/foo")));
		assertSame(Chan.getFallback(), ChanResourceAccess.resolveResourceChan(Chan.getFallback(),
				Uri.parse("chan:///res/raw/foo")));
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
