package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public class PreferencesBackupTest {
	private static byte[] export(PreferencesBackup snapshot) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		snapshot.write(output);
		return output.toByteArray();
	}

	// Independent parser for the legacy Android <map> vocabulary, not the export implementation.
	private static Map<String, Object> read(byte[] bytes) throws Exception {
		Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
				.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
		assertEquals("map", root.getTagName());
		Map<String, Object> values = new HashMap<>();
		for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (!(node instanceof Element)) continue;
			Element entry = (Element) node;
			Object value;
			switch (entry.getTagName()) {
				case "string": value = entry.getTextContent(); break;
				case "boolean": value = Boolean.valueOf(entry.getAttribute("value")); break;
				case "int": value = Integer.valueOf(entry.getAttribute("value")); break;
				case "long": value = Long.valueOf(entry.getAttribute("value")); break;
				case "float": value = Float.valueOf(entry.getAttribute("value")); break;
				case "null": value = null; break;
				case "set":
					Set<String> strings = new LinkedHashSet<>();
					for (Node item = entry.getFirstChild(); item != null; item = item.getNextSibling()) {
						if (item instanceof Element) {
							assertEquals("string", ((Element) item).getTagName());
							strings.add(item.getTextContent());
						}
					}
					value = strings;
					break;
				default: throw new AssertionError("Unexpected type");
			}
			values.put(entry.getAttribute("name"), value);
		}
		return values;
	}

	@Test public void allPreferenceTypesRoundTripIncludingEscapes() throws Exception {
		Map<String, Object> source = new HashMap<>();
		source.put("name<&\"\t\n\r", "Тема & < > \" ' \t\n\r \ud83d\ude3a");
		source.put("int", Integer.MIN_VALUE);
		source.put("long", Long.MAX_VALUE);
		source.put("float", -0.5f);
		source.put("boolean", true);
		source.put("null", null);
		source.put("set", new LinkedHashSet<>(Arrays.asList("", "один", "<&>\n\r")));
		source.put("emptySet", Collections.emptySet());
		assertEquals(source, read(export(new PreferencesBackup(source))));
	}

	@Test public void snapshotDoesNotFollowLaterCommitsOrSetChanges() throws Exception {
		Set<String> set = new LinkedHashSet<>(Arrays.asList("one", "two"));
		Map<String, Object> source = new HashMap<>();
		source.put("set", set);
		source.put("enabled", true);
		PreferencesBackup snapshot = new PreferencesBackup(source);
		set.clear();
		source.put("enabled", false);
		source.put("new", "later");
		Map<String, Object> result = read(export(snapshot));
		assertEquals(new LinkedHashSet<>(Arrays.asList("one", "two")), result.get("set"));
		assertEquals(true, result.get("enabled"));
		assertFalse(result.containsKey("new"));
	}

	@Test public void emptySettingsAreAValidMapNotAnEmptyEntry() throws Exception {
		assertTrue(read(export(new PreferencesBackup(Collections.emptyMap()))).isEmpty());
	}

	@Test public void destinationFailureAbortsExport() throws Exception {
		try {
			new PreferencesBackup(Collections.singletonMap("enabled", true)).write(new OutputStream() {
				@Override public void write(int value) throws IOException { throw new IOException("injected"); }
			});
			fail("Must propagate write failure");
		} catch (IOException expected) { assertEquals("injected", expected.getMessage()); }
	}

	@Test public void unsupportedTypeIsNotSilentlyLost() throws Exception {
		try {
			export(new PreferencesBackup(Collections.singletonMap("bad", new Object())));
			fail("Must reject unsupported type");
		} catch (IOException expected) { }
	}

	@Test public void invalidXmlCharactersAbortInsteadOfProducingBrokenBackup() throws Exception {
		for (String value : Arrays.asList("bad\u0000", "bad\ud800", "bad\uffff")) {
			try {
				export(new PreferencesBackup(Collections.singletonMap("text", value)));
				fail("Must reject invalid XML");
			} catch (IOException expected) { }
		}
	}

	@Test public void repeatedExportRemainsStableAndDoesNotCloseCaller() throws Exception {
		boolean[] closed = {false};
		ByteArrayOutputStream output = new ByteArrayOutputStream() {
			@Override public void close() { closed[0] = true; }
		};
		PreferencesBackup snapshot = new PreferencesBackup(Collections.singletonMap("line", "a\nb"));
		snapshot.write(output);
		assertFalse(closed[0]);
		assertArrayEquals(output.toByteArray(), export(snapshot));
	}
}
