package chan.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class GroupParserTest {
	@Test
	public void truncatedAttributeDoesNotReadPastEnd() {
		assertNull(GroupParser.extractAttr("href=", "href"));
		assertNull(GroupParser.extractAttr(new StringBuilder("class=link href="), "href"));
		assertNull(GroupParser.extractAttr("href='", "href"));
		assertNull(GroupParser.extractAttr("href=\"unfinished", "href"));
	}

	@Test
	public void validAttributeFormatsKeepTheirValues() {
		assertEquals("url", GroupParser.extractAttr("href='url'", "href"));
		assertEquals("url", GroupParser.extractAttr("href=\"url\"", "href"));
		assertEquals("url", GroupParser.extractAttr("href=url class=link", "href"));
		assertEquals("url", GroupParser.extractAttr("href=url", "href"));
		assertEquals("", GroupParser.extractAttr("href=\"\"", "href"));
	}

	@Test
	public void attributeBoundaryAndOverlappingPrefixAreRespected() {
		assertEquals("right", GroupParser.extractAttr("data-href=wrong href=right", "href"));
		assertEquals("right", GroupParser.extractAttr("h href=right", "href"));
		assertNull(GroupParser.extractAttr("data-href=wrong", "href"));
		assertNull(GroupParser.extractAttr("data-href=wrong href=", "href"));
		assertNull(GroupParser.extractAttr(null, "href"));
		assertNull(GroupParser.extractAttr("href=value", ""));
	}
}
