package com.mishiranu.dashchan.content;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Detached preferences snapshot, written in Android's existing SharedPreferences XML format. */
public final class PreferencesBackup {
	private final Map<String, Object> values = new LinkedHashMap<>();

	/** Capture on the preferences owner thread, including mutable StringSet values. */
	public PreferencesBackup(Map<String, ?> source) {
		for (Map.Entry<String, ?> entry : source.entrySet()) {
			Object value = entry.getValue();
			if (value instanceof Set<?>) value = new LinkedHashSet<>((Set<?>) value);
			values.put(entry.getKey(), value);
		}
	}

	public void write(OutputStream output) throws IOException {
		Writer writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
		writer.write("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>\n<map>\n");
		for (Map.Entry<String, Object> entry : values.entrySet()) {
			Object value = entry.getValue();
			String type;
			if (value == null) type = "null";
			else if (value instanceof String) type = "string";
			else if (value instanceof Set<?>) type = "set";
			else if (value instanceof Boolean) type = "boolean";
			else if (value instanceof Integer) type = "int";
			else if (value instanceof Long) type = "long";
			else if (value instanceof Float) type = "float";
			else throw new IOException("Unsupported preference value type");
			writer.write("<" + type + " name=\"");
			escape(writer, entry.getKey());
			writer.write("\"");
			if (value instanceof String) {
				writer.write(">");
				escape(writer, (String) value);
				writer.write("</string>\n");
			} else if (value instanceof Set<?>) {
				writer.write(">\n");
				for (Object item : (Set<?>) value) {
					if (!(item instanceof String)) throw new IOException("Invalid preference string set");
					writer.write("<string>");
					escape(writer, (String) item);
					writer.write("</string>\n");
				}
				writer.write("</set>\n");
			} else {
				if (value != null) writer.write(" value=\"" + value + "\"");
				writer.write(" />\n");
			}
		}
		writer.write("</map>\n");
		// Do not close the caller's ZIP entry/stream. Flush errors abort the entire backup.
		writer.flush();
	}

	private static void escape(Writer writer, String text) throws IOException {
		if (text == null) throw new IOException("Missing preference key");
		for (int i = 0; i < text.length();) {
			int codePoint = text.codePointAt(i);
			i += Character.charCount(codePoint);
			switch (codePoint) {
				case '&': writer.write("&amp;"); break;
				case '<': writer.write("&lt;"); break;
				case '>': writer.write("&gt;"); break;
				case '"': writer.write("&quot;"); break;
				case '\t': writer.write("&#9;"); break;
				case '\n': writer.write("&#10;"); break;
				case '\r': writer.write("&#13;"); break;
				default:
					if (codePoint < 0x20 || codePoint >= 0xd800 && codePoint <= 0xdfff
							|| codePoint == 0xfffe || codePoint == 0xffff) {
						throw new IOException("Preference contains an invalid XML character");
					}
					writer.write(Character.toChars(codePoint));
			}
		}
	}
}
