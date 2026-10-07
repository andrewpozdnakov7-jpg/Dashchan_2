package chan.content;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** Source guards complement real URI/key tests and the device resource smoke tests. */
public class ExtensionResourcesContractTest {
	private static String source(String path) throws Exception {
		File root = new File(".").getCanonicalFile();
		while (root != null) {
			File file = new File(root, "src/" + path);
			if (file.isFile()) return Files.readString(file.toPath(), StandardCharsets.UTF_8).replace("\r\n", "\n");
			root = root.getParentFile();
		}
		throw new AssertionError("Cannot find source: " + path);
	}

	@Test public void resourcesAreReplacedAsAnAtomicSnapshotWithoutDeprecatedMutation() throws Exception {
		String owner = source("chan/content/ChanResources.java");
		assertTrue(owner.contains("createConfigurationContext(copy)"));
		assertTrue(owner.contains("private volatile Snapshot snapshot"));
		assertTrue(owner.contains("NEXT_GENERATION.incrementAndGet()"));
		assertTrue(owner.contains("if (configuration != null && copy.equals(configuration)) return false"));
		String manager = source("chan/content/ChanManager.java");
		assertFalse(manager.contains("getResources().updateConfiguration("));
		assertFalse(manager.contains("getResourcesForApplication("));
		assertTrue(manager.contains("createPackageContext(chanItem.packageName, 0)"));
		assertTrue(manager.contains("createEffectiveConfiguration(application.getResources().getConfiguration())"));
	}

	@Test public void startupLocaleHelperHasNoManagerOrGlobalSideEffects() throws Exception {
		String locale = source("com/mishiranu/dashchan/content/LocaleManager.java");
		String helper = locale.substring(locale.indexOf("public Configuration createEffectiveConfiguration("),
				locale.indexOf("private void updateConfiguration("));
		assertTrue(helper.contains("new Configuration(base)"));
		assertTrue(helper.contains("Preferences.getLocale()"));
		assertTrue(helper.contains("effective.setLocales("));
		assertFalse(helper.contains("ChanManager"));
		assertFalse(helper.contains("Locale.setDefault"));
		assertFalse(helper.contains("applicationContext ="));
	}

	@Test public void publicResourcesApiAndInvalidResourceGuardsRemain() throws Exception {
		String config = source("chan/content/ChanConfiguration.java");
		assertTrue(config.contains("@Public\n\tpublic final Resources getResources()"));
		assertTrue(config.contains("if (chanResources.update(configuration)) resourceUris.clear()"));
		assertEquals(2, config.split("catch \\(Resources.NotFoundException e\\)", -1).length - 1);
		assertTrue(config.contains("chan.packageName.equals(resources.getResourcePackageName(id))"));
		assertTrue(config.contains("uri == null || !uri.isHierarchical()"));
		assertTrue(source("chan/content/ChanManager.java").contains("public static final int MAX_VERSION = 1"));
	}

	@Test public void imageCacheUsesCurrentGenerationAndRejectsObsoleteCompletion() throws Exception {
		String loader = source("com/mishiranu/dashchan/content/ImageLoader.java");
		assertTrue(loader.contains("chan = ChanResourceAccess.resolveResourceChan(chan, uri)"));
		assertTrue(loader.contains("resource ? chan.configuration.getResourcesGenerationInternal() : 0L"));
		assertTrue(loader.contains("ImageMemoryKey.create(chan.name, targetSize, key, resource, resourcesGeneration)"));
		assertTrue(loader.contains("if (obsoleteResource)"));
		assertTrue(loader.contains("callback.onResourcesChanged(chan, uri, key, memoryKey, fromCacheOnly)"));
		assertTrue(loader.contains("taskKey.equals(currentTaskKey)"));
		assertTrue(loader.contains("INSTANCE.loadImage(chan, uri, key, fromCacheOnly, Target.this)"));
		assertFalse(loader.contains("bitmapCache.clear()"));
	}

	@Test public void allReadersResolveAtPointOfUseAndExtensionMapIsSafelyPublished() throws Exception {
		assertTrue(source("chan/content/ChanManager.java").contains("private volatile Map<String, Extension> extensions"));
		for (String path : new String[] {"com/mishiranu/dashchan/content/ImageLoader.java",
				"com/mishiranu/dashchan/content/async/ReadFileTask.java",
				"com/mishiranu/dashchan/content/async/SendLocalArchiveTask.java"}) {
			String reader = source(path);
			assertTrue(path, reader.contains("ChanResourceAccess.resolveResourceChan(chan,"));
			assertTrue(path, reader.contains("resourceChan.configuration.readResourceUri("));
			assertFalse(path, reader.contains("!chan.configuration.readResourceUri("));
		}
		String access = source("chan/content/ChanResourceAccess.java");
		assertTrue(access.contains("ChanResourceUri.resolveCurrent("));
		assertTrue(access.contains("Chan::get"));
		assertFalse(access.contains("@Public"));
	}

	@Test public void persistentIdentityAndPostComparisonDoNotContainRuntimeGeneration() throws Exception {
		String uri = source("chan/content/ChanResourceUri.java");
		assertTrue(uri.contains("static String build(String chanName, String type, String name)"));
		assertFalse(uri.contains("?g="));
		String config = source("chan/content/ChanConfiguration.java");
		String builder = config.substring(config.indexOf("public final Uri getResourceUri("),
				config.indexOf("public final boolean readResourceUri("));
		assertFalse(builder.contains("snapshot.generation"));
		assertTrue(source("com/mishiranu/dashchan/content/model/Post.java")
				.contains("this.uri = ChanResourceAccess.canonicalize(uri)"));
		String pages = source("com/mishiranu/dashchan/content/database/PagesDatabase.java");
		assertTrue(pages.contains("contentChanged = !serialized.post.isContentEqual(oldPost, threadKey.chanName)"));
		assertTrue(pages.contains("if (contentChanged)"));
	}

	@Test public void legacyComparisonHasThreadOwnerAndDoesNotChangePersistentSerialization() throws Exception {
		String post = source("com/mishiranu/dashchan/content/model/Post.java");
		assertTrue(post.contains("return isContentEqual(another, null)"));
		assertTrue(post.contains("serialize(writer, true, null)"));
		assertTrue(post.contains("serialize(writer, false, chanName)"));
		assertTrue(post.contains("another.serialize(anotherWriter, false, chanName)"));
		assertTrue(post.contains("includeVote ? icon.uri.toString()"));
		assertTrue(post.contains("normalizeResourceUriForContentComparison(icon.uri, comparisonChanName)"));
		String pages = source("com/mishiranu/dashchan/content/database/PagesDatabase.java");
		int comparison = pages.indexOf("contentChanged = !serialized.post.isContentEqual(oldPost, threadKey.chanName)");
		int editedGate = pages.indexOf("if (contentChanged)", comparison);
		int markEdited = pages.indexOf("flags = FlagUtils.set(flags, Schema.Posts.Flags.MARK_EDITED, true)", editedGate);
		assertTrue(comparison >= 0 && editedGate > comparison && markEdited > editedGate);
		String access = source("chan/content/ChanResourceAccess.java");
		String helper = access.substring(access.indexOf("public static String normalizeResourceUriForContentComparison("));
		assertFalse(helper.contains("Chan::get"));
		assertFalse(helper.contains("ChanManager"));
	}

	@Test public void archiveAndChanCacheKeysIgnoreMetadataWithoutChangingNetworkKeys() throws Exception {
		String archive = source("com/mishiranu/dashchan/content/async/SendLocalArchiveTask.java");
		assertTrue(archive.contains("ChanResourceAccess.canonicalize(chan.locator.convert(icon.uri))"));
		assertTrue(archive.contains("String simpleUri = resource ? iconUri.toString()"));
		assertTrue(archive.indexOf("ChanResourceAccess.canonicalize(") < archive.indexOf("hasher.calculate(simpleUri)"));
		assertTrue(source("com/mishiranu/dashchan/content/CacheManager.java")
				.contains("data = ChanResourceAccess.canonicalize(uri).toString()"));
	}

	@Test public void iconsResolveCurrentResourcesAndApplicationConfigurationIsForwarded() throws Exception {
		String chan = source("chan/content/Chan.java");
		assertTrue(chan.contains("final int iconResId"));
		assertFalse(chan.contains("final Drawable icon"));
		String manager = source("chan/content/ChanManager.java");
		assertTrue(manager.contains("resources.getDrawable(chan.iconResId, null)"));
		assertTrue(source("com/mishiranu/dashchan/content/MainApplication.java")
				.contains("LocaleManager.getInstance().onConfigurationChanged(newConfig)"));
	}
}
