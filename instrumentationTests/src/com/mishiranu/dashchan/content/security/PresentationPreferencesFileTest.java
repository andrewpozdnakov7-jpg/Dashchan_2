package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import android.content.Context;
import android.util.AtomicFile;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Disposable XML fixtures only; never calls full reset or accesses real preferences.xml. */
public class PresentationPreferencesFileTest {
	private File directory, file;
	@Before public void prepare() throws Exception {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		directory = new File(context.getCacheDir(), "retained-prefs-test-" + java.util.UUID.randomUUID());
		assertTrue(directory.mkdir()); file = new File(directory, "fixture.xml");
	}
	@After public void cleanup() throws Exception { PrivacyResetFiles.delete(directory); }
	private void write(String xml) throws Exception {
		try (FileOutputStream out = new FileOutputStream(file)) { out.write(xml.getBytes(StandardCharsets.UTF_8)); }
	}
	private Map<String, Object> read() throws Exception { return PresentationPreferencesFile.read(new AtomicFile(file), key -> true); }
	@Test public void secondRetentionUsesNewDiskValuesInTheSameProcessAndDropsPersonalKeys() throws Exception {
		write("<map><string name=\"theme\">A</string><string name=\"video_buttons_layout_v1\">layout-A</string>"
				+ "<string name=\"dvach_password\">private-fixture</string></map>");
		PresentationPreferencesFile.retain(file, PrivacyResetPolicy::keepPreference, Collections.emptyMap());
		assertEquals("A", read().get("theme")); assertFalse(read().containsKey("dvach_password"));
		// Simulate a different, already stopped writer's committed XML; do NOT replace the reset process.
		write("<map><string name=\"theme\">B</string><string name=\"video_buttons_layout_v1\">layout-B</string>"
				+ "<int name=\"text_scale\" value=\"125\"/><string name=\"settings_search_history\">private-fixture</string></map>");
		PresentationPreferencesFile.retain(file, PrivacyResetPolicy::keepPreference, Collections.emptyMap());
		assertEquals("B", read().get("theme")); assertEquals("layout-B", read().get("video_buttons_layout_v1"));
		assertEquals(125, read().get("text_scale")); assertFalse(read().containsKey("settings_search_history"));
	}
	@Test public void supportedTypesEscapingAndForcedTrackingOffRoundTrip() throws Exception {
		write("<map><string name=\"theme\">A &amp; B &lt;fixture&gt;</string><boolean name=\"flag\" value=\"true\"/>"
				+ "<int name=\"number\" value=\"42\"/><long name=\"long\" value=\"12345678901\"/>"
				+ "<float name=\"float\" value=\"1.25\"/><set name=\"set\"><string>one</string><string>two</string></set></map>");
		PresentationPreferencesFile.retain(file, key -> true, Collections.singletonMap("flag", false));
		Map<String, Object> values = read();
		assertEquals("A & B <fixture>", values.get("theme")); assertEquals(false, values.get("flag"));
		assertEquals(42, values.get("number")); assertEquals(12345678901L, values.get("long"));
		assertEquals(1.25f, values.get("float"));
		assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("one", "two")), values.get("set"));
	}
	@Test public void invalidSourceFailsBeforeAnyReplacement() throws Exception {
		write("<map><string name=\"theme\">unfinished");
		byte[] before = new AtomicFile(file).readFully();
		try { PresentationPreferencesFile.retain(file, key -> true, Collections.emptyMap()); fail("Bad XML accepted"); }
		catch (IOException expected) {}
		assertArrayEquals(before, new AtomicFile(file).readFully());
	}
	@Test public void declarationsAreRejectedWithoutReplacingTheFile() throws Exception {
		write("<!DOCTYPE map [<!ENTITY fixture 'test'>]><map><string name=\"theme\">&fixture;</string></map>");
		byte[] before = new AtomicFile(file).readFully();
		try { PresentationPreferencesFile.retain(file, key -> true, Collections.emptyMap()); fail("DTD accepted"); }
		catch (IOException expected) {}
		assertArrayEquals(before, new AtomicFile(file).readFully());
	}
	@Test public void startupNoticeCounterAlsoUsesTheFreshDiskSnapshot() throws Exception {
		write("<map><int name=\"material3-acknowledgements\" value=\"1\"/></map>");
		PresentationPreferencesFile.retain(file, "material3-acknowledgements"::equals, Collections.emptyMap());
		write("<map><int name=\"material3-acknowledgements\" value=\"3\"/><string name=\"private\">fixture</string></map>");
		PresentationPreferencesFile.retain(file, "material3-acknowledgements"::equals, Collections.emptyMap());
		assertEquals(3, read().get("material3-acknowledgements")); assertEquals(1, read().size());
	}
}
