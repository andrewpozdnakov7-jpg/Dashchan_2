package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Source-policy regression tests. These do not simulate an Android backup transport. */
public class AndroidBackupPolicyTest {
	private static final String ANDROID = "http://schemas.android.com/apk/res/android";
	private static final Set<String> DOMAINS = new HashSet<>(Arrays.asList("root", "file", "database",
			"sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref"));

	private static File sourceRoot() throws Exception {
		File root = new File(".").getCanonicalFile();
		while (root != null) {
			if (new File(root, "AndroidManifest.xml").isFile() && new File(root, "build.gradle").isFile()) {
				return root;
			}
			root = root.getParentFile();
		}
		throw new AssertionError("Cannot find the source tree for backup policy tests");
	}

	private static Element read(String path) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		return factory.newDocumentBuilder().parse(new File(sourceRoot(), path)).getDocumentElement();
	}

	private static void assertAllDomainsExcluded(Element section) {
		Set<String> domains = new HashSet<>();
		for (Node node = section.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (!(node instanceof Element)) continue;
			Element rule = (Element) node;
			assertEquals("Only whole-domain exclusions are allowed", "exclude", rule.getTagName());
			assertEquals(".", rule.getAttribute("path"));
			assertTrue("Duplicate domain", domains.add(rule.getAttribute("domain")));
		}
		assertEquals("A missing domain would allow a new secret file into backup", DOMAINS, domains);
	}

	@Test public void android11ExcludesEveryDomain() throws Exception {
		Element root = read("res/xml/app_backup_rules.xml");
		assertEquals("full-backup-content", root.getTagName());
		assertAllDomainsExcluded(root);
	}

	@Test public void cloudAndDeviceTransferHaveTheSameClosedPolicy() throws Exception {
		Element root = read("res/xml/app_data_extraction_rules.xml");
		assertEquals("data-extraction-rules", root.getTagName());
		Set<String> sections = new HashSet<>();
		for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
			if (!(node instanceof Element)) continue;
			Element section = (Element) node;
			assertTrue(sections.add(section.getTagName()));
			assertAllDomainsExcluded(section);
		}
		assertEquals(new HashSet<>(Arrays.asList("cloud-backup", "device-transfer")), sections);
	}

	@Test public void bothDistributionsInheritThePolicyWithoutAnOverride() throws Exception {
		Element main = (Element) read("AndroidManifest.xml").getElementsByTagName("application").item(0);
		assertEquals("false", main.getAttributeNS(ANDROID, "allowBackup"));
		assertEquals("@xml/app_backup_rules", main.getAttributeNS(ANDROID, "fullBackupContent"));
		assertEquals("@xml/app_data_extraction_rules", main.getAttributeNS(ANDROID, "dataExtractionRules"));
		File[] distributionDirectories = new File(sourceRoot(), "distribution").listFiles();
		assertNotNull(distributionDirectories);
		for (File directory : distributionDirectories) {
			String path = "distribution/" + directory.getName() + "/AndroidManifest.xml";
			if (!new File(sourceRoot(), path).isFile()) continue;
			Element application = (Element) read(path).getElementsByTagName("application").item(0);
			if (application == null) continue;
			for (String attribute : Arrays.asList("allowBackup", "fullBackupContent", "dataExtractionRules")) {
				assertFalse(path + " must not override " + attribute, application.hasAttributeNS(ANDROID, attribute));
			}
		}
	}
}
