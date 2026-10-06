package chan.content;

import static org.junit.Assert.*;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ChanResourceUriTest {
	private static List<String> path(String type, String name) {
		return Arrays.asList("res", type, name);
	}

	@Test public void generatedUriContainsOwnerPathAndGeneration() throws Exception {
		URI uri = new URI(ChanResourceUri.build("dvach", "drawable", "browser_firefox", 123L));
		assertEquals("chan", uri.getScheme());
		assertEquals("dvach", uri.getAuthority());
		assertEquals("/res/drawable/browser_firefox", uri.getPath());
		assertEquals("g=123", uri.getQuery());
	}

	@Test public void supportsHyphenatedChanAndDottedResourceName() {
		assertEquals("chan://reddit-web-reader/res/style/Theme.App?g=4",
				ChanResourceUri.build("reddit-web-reader", "style", "Theme.App", 4L));
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
			assertNull(ChanResourceUri.build("dvach", "drawable", name, 1L));
		}
	}

	@Test public void differentGenerationsProduceDifferentUris() {
		assertNotEquals(ChanResourceUri.build("dvach", "drawable", "icon", 1L),
				ChanResourceUri.build("dvach", "drawable", "icon", 2L));
	}
}
