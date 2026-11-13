package com.mishiranu.dashchan.content.storage;

import chan.text.JsonSerial;
import chan.text.ParseException;
import java.io.File;
import java.io.IOException;
import java.util.Collection;

/** Accepted output, alongside (never instead of) the original attachment draft. No media bytes. */
public final class AttachmentConversion {
	public final String hash, name, codec, signature;
	public final long profile, before, after;

	public AttachmentConversion(String hash, String name, String codec, String signature,
			long profile, long before, long after) {
		this.hash = hash; this.name = name; this.codec = codec; this.signature = signature;
		this.profile = profile; this.before = before; this.after = after;
	}
	public boolean isValid() {
		return hash != null && hash.matches("[0-9a-f]{64}") && name != null && !name.isEmpty()
				&& codec != null && !codec.isEmpty() && signature != null && !signature.isEmpty()
				&& profile >= 0 && before > 0 && after > 0 && after <= Integer.MAX_VALUE;
	}
	public boolean matches(String signature, long profile, File output) {
		return isValid() && this.signature.equals(signature) && this.profile == profile
				&& output != null && output.isFile() && output.length() == after;
	}
	public void collectHash(Collection<String> hashes) {
		if (isValid()) hashes.add(hash);
	}
	public void serialize(JsonSerial.Writer writer) throws IOException {
		writer.startObject();
		writer.name("hash"); writer.value(hash);
		writer.name("name"); writer.value(name);
		writer.name("codec"); writer.value(codec);
		writer.name("signature"); writer.value(signature);
		writer.name("profile"); writer.value(profile);
		writer.name("before"); writer.value(before);
		writer.name("after"); writer.value(after);
		writer.endObject();
	}
	public static AttachmentConversion deserialize(JsonSerial.Reader reader) throws IOException, ParseException {
		String hash = null, name = null, codec = null, signature = null;
		long profile = -1, before = 0, after = 0;
		reader.startObject();
		while (!reader.endStruct()) {
			switch (reader.nextName()) {
				case "hash": hash = reader.nextString(); break;
				case "name": name = reader.nextString(); break;
				case "codec": codec = reader.nextString(); break;
				case "signature": signature = reader.nextString(); break;
				case "profile": profile = reader.nextLong(); break;
				case "before": before = reader.nextLong(); break;
				case "after": after = reader.nextLong(); break;
				default: reader.skip(); break;
			}
		}
		AttachmentConversion result = new AttachmentConversion(hash, name, codec, signature, profile, before, after);
		return result.isValid() ? result : null;
	}
}
