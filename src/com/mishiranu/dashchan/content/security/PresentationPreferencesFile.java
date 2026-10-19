package com.mishiranu.dashchan.content.security;

import android.util.AtomicFile;
import android.util.Xml;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

/** Fresh disk snapshot after writers stop. Never opens Android's process-cached SharedPreferences. */
final class PresentationPreferencesFile {
	private static final int MAX_BYTES = 16 * 1024 * 1024;
	private PresentationPreferencesFile() {}

	static synchronized void retain(File path, Predicate<String> keep, Map<String, Object> forced) throws IOException {
		AtomicFile file = new AtomicFile(path);
		Map<String, Object> retained = read(file, keep);
		retained.putAll(forced);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		XmlSerializer xml = Xml.newSerializer();
		xml.setOutput(bytes, StandardCharsets.UTF_8.name());
		xml.startDocument(StandardCharsets.UTF_8.name(), true); xml.startTag(null, "map");
		for (Map.Entry<String, Object> entry : retained.entrySet()) write(xml, entry.getKey(), entry.getValue());
		xml.endTag(null, "map"); xml.endDocument(); xml.flush();
		if (bytes.size() > MAX_BYTES) throw new IOException("Retained preferences exceed size budget");
		FileOutputStream output = null;
		try {
			output = file.startWrite(); bytes.writeTo(output); output.getFD().sync();
			file.finishWrite(output); output = null;
			if (!retained.equals(read(file, key -> true))) throw new IOException("Retained preferences readback failed");
		} catch (IOException | RuntimeException e) {
			if (output != null) file.failWrite(output);
			throw e;
		}
	}

	static Map<String, Object> read(AtomicFile file, Predicate<String> keep) throws IOException {
		Map<String, Object> values = new LinkedHashMap<>();
		if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) return values;
		try (FileInputStream input = file.openRead()) {
			if (input.getChannel().size() > MAX_BYTES) throw new IOException("Preferences exceed read budget");
			XmlPullParser xml = Xml.newPullParser();
			xml.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
			xml.setInput(input, StandardCharsets.UTF_8.name());
			int event;
			do { event = next(xml); } while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT);
			if (event != XmlPullParser.START_TAG || !"map".equals(xml.getName())) throw new IOException("Invalid preferences root");
			int depth = xml.getDepth();
			while ((event = next(xml)) != XmlPullParser.END_DOCUMENT) {
				if (event == XmlPullParser.END_TAG && xml.getDepth() == depth) break;
				if (event == XmlPullParser.START_TAG) {
					String key = xml.getAttributeValue(null, "name");
					if (key == null) throw new IOException("Preference name absent");
					if (keep.test(key)) {
						if (values.containsKey(key)) throw new IOException("Duplicate retained preference");
						values.put(key, value(xml));
					} else skip(xml);
				}
			}
			if (event == XmlPullParser.END_DOCUMENT) throw new IOException("Incomplete preferences map");
			while ((event = next(xml)) != XmlPullParser.END_DOCUMENT) {
				if (event == XmlPullParser.START_TAG || xml.getText() != null && !xml.getText().trim().isEmpty()) {
					throw new IOException("Trailing preferences content");
				}
			}
			return values;
		} catch (XmlPullParserException | NumberFormatException e) {
			// Parser messages may contain private values; do not propagate them into diagnostics.
			throw new IOException("Invalid preferences XML");
		}
	}

	private static int next(XmlPullParser xml) throws IOException, XmlPullParserException {
		int event = xml.nextToken();
		if (event == XmlPullParser.DOCDECL || event == XmlPullParser.ENTITY_REF && xml.getText() == null) {
			throw new IOException("Unsupported preferences declaration");
		}
		return event;
	}
	private static void skip(XmlPullParser xml) throws IOException, XmlPullParserException {
		int depth = xml.getDepth(), event;
		do {
			event = next(xml);
			if (event == XmlPullParser.END_DOCUMENT) throw new IOException("Incomplete preference");
		} while (event != XmlPullParser.END_TAG || xml.getDepth() != depth);
	}
	private static String text(XmlPullParser xml) throws IOException, XmlPullParserException {
		StringBuilder value = new StringBuilder(); int event;
		while ((event = next(xml)) != XmlPullParser.END_TAG) {
			if (event == XmlPullParser.START_TAG || event == XmlPullParser.END_DOCUMENT) throw new IOException("Invalid preference text");
			if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT || event == XmlPullParser.ENTITY_REF
					|| event == XmlPullParser.IGNORABLE_WHITESPACE) value.append(xml.getText());
		}
		return value.toString();
	}
	private static Object value(XmlPullParser xml) throws IOException, XmlPullParserException {
		String type = xml.getName();
		if ("string".equals(type)) return text(xml);
		if ("set".equals(type)) {
			Set<String> result = new LinkedHashSet<>(); int event;
			while ((event = next(xml)) != XmlPullParser.END_TAG) {
				if (event == XmlPullParser.END_DOCUMENT) throw new IOException("Incomplete preference set");
				if (event == XmlPullParser.START_TAG) {
					if (!"string".equals(xml.getName())) throw new IOException("Invalid preference set");
					result.add(text(xml));
				}
			}
			return result;
		}
		String raw = xml.getAttributeValue(null, "value");
		Object result;
		if (raw == null) throw new IOException("Preference value absent");
		switch (type) {
			case "boolean":
				if (!"true".equals(raw) && !"false".equals(raw)) throw new IOException("Invalid boolean preference");
				result = Boolean.valueOf(raw); break;
			case "int": result = Integer.valueOf(raw); break;
			case "long": result = Long.valueOf(raw); break;
			case "float": result = Float.valueOf(raw); break;
			default: throw new IOException("Unsupported retained preference type");
		}
		if (!text(xml).trim().isEmpty()) throw new IOException("Unexpected scalar preference text");
		return result;
	}
	private static void write(XmlSerializer xml, String key, Object value) throws IOException {
		String type = value instanceof String ? "string" : value instanceof Boolean ? "boolean"
				: value instanceof Integer ? "int" : value instanceof Long ? "long" : value instanceof Float ? "float"
				: value instanceof Set<?> ? "set" : null;
		if (type == null) throw new IOException("Unsupported retained preference value");
		xml.startTag(null, type); xml.attribute(null, "name", key);
		if (value instanceof String) xml.text((String) value);
		else if (value instanceof Set<?>) {
			for (Object item : (Set<?>) value) {
				if (!(item instanceof String)) throw new IOException("Invalid retained preference set");
				xml.startTag(null, "string"); xml.text((String) item); xml.endTag(null, "string");
			}
		} else xml.attribute(null, "value", value.toString());
		xml.endTag(null, type);
	}
}
