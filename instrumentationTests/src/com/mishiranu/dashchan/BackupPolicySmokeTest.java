package com.mishiranu.dashchan;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.XmlResourceParser;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

/** Tests the installed backup flag and compiled XML, not the transport or existing cloud copies. */
@RunWith(AndroidJUnit4.class)
public class BackupPolicySmokeTest {
	private static final Set<String> DOMAINS = new HashSet<>(Arrays.asList("root", "file", "database",
			"sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref"));

	@Test public void installedApplicationUsesTheSharedClosedBackupPolicy() throws Exception {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		ApplicationInfo info = context.getApplicationInfo();
		assertEquals(0, info.flags & ApplicationInfo.FLAG_ALLOW_BACKUP);
		try (XmlResourceParser parser = context.getResources().getXml(R.xml.app_backup_rules)) {
			assertSection(parser, "full-backup-content");
		}
		try (XmlResourceParser parser = context.getResources().getXml(R.xml.app_data_extraction_rules)) {
			assertSection(parser, "cloud-backup");
			assertSection(parser, "device-transfer");
		}
	}

	private static void assertSection(XmlResourceParser parser, String section) throws Exception {
		while (parser.next() != XmlPullParser.END_DOCUMENT) {
			if (parser.getEventType() != XmlPullParser.START_TAG || !section.equals(parser.getName())) continue;
			int depth = parser.getDepth();
			Set<String> domains = new HashSet<>();
			while (parser.next() != XmlPullParser.END_DOCUMENT) {
				if (parser.getEventType() == XmlPullParser.END_TAG && parser.getDepth() == depth) {
					assertEquals(DOMAINS, domains);
					return;
				}
				if (parser.getEventType() == XmlPullParser.START_TAG) {
					assertEquals("exclude", parser.getName());
					assertEquals(".", parser.getAttributeValue(null, "path"));
					assertTrue(domains.add(parser.getAttributeValue(null, "domain")));
				}
			}
			fail("Unclosed backup section");
		}
		fail("Missing backup section: " + section);
	}
}
