package com.mishiranu.dashchan.content;

import static org.junit.Assert.*;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Source-resource consistency; not a replacement for Android lint or device tests. */
public class ResourceLocaleContractTest {
	private static Path languageRoot() throws Exception {
		File root = new File(".").getCanonicalFile();
		while (root != null) {
			Path lang = root.toPath().resolve("lang");
			if (Files.isRegularFile(lang.resolve("values/strings.xml"))) return lang;
			root = root.getParentFile();
		}
		throw new AssertionError("Cannot find the source language resources");
	}

	private static Set<String> stringNames(Path path) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
		// These standard JAXP properties are supported by the host JDK running
		// JVM tests, but their XMLConstants fields are absent from android.jar.
		factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
		factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "");
		factory.setExpandEntityReferences(false);
		try (InputStream input = Files.newInputStream(path)) {
			NodeList strings = factory.newDocumentBuilder().parse(input).getElementsByTagName("string");
			Set<String> names = new HashSet<>();
			for (int i = 0; i < strings.getLength(); i++) {
				names.add(((Element) strings.item(i)).getAttribute("name"));
			}
			return names;
		}
	}

	@Test
	public void everyTranslationHasADefaultString() throws Exception {
		Path lang = languageRoot();
		List<Path> files;
		try (Stream<Path> stream = Files.walk(lang)) {
			files = stream.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".xml"))
					.collect(Collectors.toList());
		}
		Set<String> defaults = new HashSet<>();
		for (Path file : files) {
			if (file.getParent().getFileName().toString().equals("values")) {
				defaults.addAll(stringNames(file));
			}
		}
		assertFalse("Default string inventory is empty", defaults.isEmpty());
		int checked = 0;
		for (Path file : files) {
			if (file.getParent().getFileName().toString().startsWith("values-")) {
				for (String name : stringNames(file)) {
					assertTrue("Extra translation: " + lang.relativize(file) + ": " + name,
							defaults.contains(name));
					checked++;
				}
			}
		}
		assertTrue("Localized string inventory is empty", checked > 0);
	}
}
