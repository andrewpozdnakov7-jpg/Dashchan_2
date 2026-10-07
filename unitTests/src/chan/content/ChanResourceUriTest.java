package chan.content;

import static org.junit.Assert.*;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class ChanResourceUriTest {
	private static List<String> path(String type, String name) {
		return Arrays.asList("res", type, name);
	}

	@Test public void generatedUriContainsStableOwnerAndPathOnly() throws Exception {
		URI uri = new URI(ChanResourceUri.build("dvach", "drawable", "browser_firefox"));
		assertEquals("chan", uri.getScheme());
		assertEquals("dvach", uri.getAuthority());
		assertEquals("/res/drawable/browser_firefox", uri.getPath());
		assertNull(uri.getQuery());
		assertNull(uri.getFragment());
	}

	@Test public void supportsHyphenatedChanAndDottedResourceName() {
		assertEquals("chan://reddit-web-reader/res/style/Theme.App",
				ChanResourceUri.build("reddit-web-reader", "style", "Theme.App"));
	}

	@Test public void legacyEmptyAuthorityIsAccepted() {
		assertTrue(ChanResourceUri.accepts("chan", null, "dvach", path("drawable", "icon")));
		assertTrue(ChanResourceUri.accepts("chan", "", "dvach", path("drawable", "icon")));
	}

	@Test public void matchingAuthorityIsAccepted() {
		assertTrue(ChanResourceUri.accepts("chan", "dvach", "dvach", path("drawable", "icon")));
	}

	@Test public void otherAuthorityAndFallbackChanAreRejected() {
		assertFalse(ChanResourceUri.accepts("chan", "fourchan", "dvach", path("drawable", "icon")));
		assertFalse(ChanResourceUri.accepts("chan", "dvach:80", "dvach", path("drawable", "icon")));
		assertFalse(ChanResourceUri.accepts("chan", "user@dvach", "dvach", path("drawable", "icon")));
		assertFalse(ChanResourceUri.accepts("chan", "", null, path("drawable", "icon")));
	}

	@Test public void wrongSchemeOrPathIsRejected() {
		assertFalse(ChanResourceUri.accepts("https", "dvach", "dvach", path("drawable", "icon")));
		assertFalse(ChanResourceUri.accepts("chan", "dvach", "dvach", Arrays.asList("other", "drawable", "icon")));
		assertFalse(ChanResourceUri.accepts("chan", "dvach", "dvach", Arrays.asList("res", "drawable")));
		assertFalse(ChanResourceUri.accepts("chan", "dvach", "dvach", null));
	}

	@Test public void embeddedPackageAndPathInjectionIsRejected() {
		for (String name : new String[] {"android:drawable/icon", "../icon", "drawable/icon", "", "icon?g=9"}) {
			assertFalse(ChanResourceUri.accepts("chan", "dvach", "dvach", path("drawable", name)));
			assertNull(ChanResourceUri.build("dvach", "drawable", name));
		}
	}

	@Test public void legacyQueryAndFragmentNeverParticipateInResourceIdentityOrHash() throws Exception {
		String canonical = ChanResourceUri.build("dvach", "raw", "foo");
		MessageDigest hash = MessageDigest.getInstance("SHA-256");
		byte[] expected = hash.digest(canonical.getBytes(StandardCharsets.UTF_8));
		for (String uri : new String[] {canonical, canonical + "?g=1", canonical + "?g=999#old",
				canonical + "#old?g=123", canonical + "?g=not-a-generation"}) {
			assertEquals(canonical, ChanResourceUri.canonicalIdentity(uri));
			assertArrayEquals(expected, hash.digest(ChanResourceUri.canonicalIdentity(uri)
					.getBytes(StandardCharsets.UTF_8)));
			URI parsed = new URI(uri);
			assertTrue(ChanResourceUri.accepts(parsed.getScheme(), parsed.getAuthority(), "dvach",
					Arrays.asList(parsed.getPath().substring(1).split("/"))));
		}
	}

	@Test public void canonicalizationPreservesOwnerAndEncodedPathButNotOldMetadata() {
		assertEquals("chan:///res/raw/foo", ChanResourceUri.canonicalIdentity("chan:///res/raw/foo?g=1#old"));
		assertEquals("chan://dvach/res/raw/foo%3Fbar",
				ChanResourceUri.canonicalIdentity("chan://dvach/res/raw/foo%3Fbar?g=2"));
		assertNotEquals(ChanResourceUri.canonicalIdentity("chan://dvach/res/raw/foo?g=1"),
				ChanResourceUri.canonicalIdentity("chan://fourchan/res/raw/foo?g=1"));
		String network = "https://example.org/icon?revision=2#section";
		assertEquals(network, ChanResourceUri.canonicalIdentity(network));
		assertEquals("chan:opaque?g=1", ChanResourceUri.canonicalIdentity("chan:opaque?g=1"));
		assertNull(ChanResourceUri.canonicalIdentity(null));
	}

	@Test public void legacyContentIdentityUsesOnlyTheSuppliedThreadOwner() {
		String expected = "chan://dvach/res/raw/foo";
		assertEquals(expected, ChanResourceUri.contentComparisonIdentity("chan:///res/raw/foo", "dvach"));
		assertEquals(expected, ChanResourceUri.contentComparisonIdentity("chan:///res/raw/foo?g=123#old", "dvach"));
		assertEquals("chan://fourchan/res/raw/foo",
				ChanResourceUri.contentComparisonIdentity("chan:///res/raw/foo", "fourchan"));
	}

	@Test public void comparisonIgnoresMetadataButPreservesExplicitOwners() {
		String expected = "chan://dvach/res/raw/foo";
		for (String uri : new String[] {expected, expected + "?g=1", expected + "?g=999#old"}) {
			assertEquals(expected, ChanResourceUri.contentComparisonIdentity(uri, "fourchan"));
		}
		assertNotEquals(expected, ChanResourceUri.contentComparisonIdentity("chan://fourchan/res/raw/foo", "dvach"));
	}

	@Test public void unknownComparisonOwnerIsNeverGuessed() {
		for (String owner : new String[] {null, ""}) {
			assertEquals("chan:///res/raw/foo", ChanResourceUri.contentComparisonIdentity("chan:///res/raw/foo", owner));
			assertNotEquals(ChanResourceUri.contentComparisonIdentity("chan:///res/raw/foo", owner),
					ChanResourceUri.contentComparisonIdentity("chan://dvach/res/raw/foo", owner));
		}
	}

	@Test public void nonResourceComparisonUrisAreUnchanged() {
		for (String uri : new String[] {"https://example.org/a.png?g=1#part", "file:///a.png#part",
				"content://provider/a?g=1", "chan:opaque?g=1", "chan:///other/raw/foo?g=1",
				"chan:///res/raw/foo/extra?g=1", "chan:///res/raw/foo%2Fbar?g=1"}) {
			assertEquals(uri, ChanResourceUri.contentComparisonIdentity(uri, "dvach"));
		}
		assertNull(ChanResourceUri.contentComparisonIdentity(null, "dvach"));
	}

	@Test public void comparisonDoesNotChangePersistentIdentity() {
		String legacy = "chan:///res/raw/foo";
		assertEquals("chan://dvach/res/raw/foo", ChanResourceUri.contentComparisonIdentity(legacy, "dvach"));
		assertEquals(legacy, ChanResourceUri.canonicalIdentity(legacy));
		assertEquals("chan://dvach/res/raw/foo", ChanResourceUri.build("dvach", "raw", "foo"));
	}

	@Test public void hotReplacementUsesCurrentLookupForModernAndAuthorityLessUris() {
		Object stale = new Object();
		Object first = new Object();
		Object replacement = new Object();
		AtomicReference<Object> current = new AtomicReference<>(first);
		assertSame(first, ChanResourceUri.resolveCurrent(stale, "dvach", "dvach", name -> {
			assertEquals("dvach", name);
			return current.get();
		}));
		current.set(replacement);
		for (String authority : new String[] {"dvach", "", null}) {
			assertSame(replacement, ChanResourceUri.resolveCurrent(stale, "dvach", authority, name -> {
				assertEquals("dvach", name);
				return current.get();
			}));
		}
	}

	@Test public void authorityOverridesStaleFallbackAndUnnamedFallbackIsRetained() {
		Object stale = new Object();
		Object owner = new Object();
		assertSame(owner, ChanResourceUri.resolveCurrent(stale, "fourchan", "dvach", name -> {
			assertEquals("dvach", name);
			return owner;
		}));
		assertSame(stale, ChanResourceUri.resolveCurrent(stale, null, null, name -> {
			throw new AssertionError("Unnamed fallback must not query the manager");
		}));
	}
}
