package com.mishiranu.dashchan.ui.gallery;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded local attachment descriptors, never media bytes or player/Android objects. */
public final class GalleryRestoreCodec {
	public static final int MAX_ITEMS = 20000, MAX_BYTES = 8 * 1024 * 1024;
	private static final int MAGIC = 0x53475231, MAX_STRING = 8192;
	public record Item(String uri, String thumbnail, String fileName, String board, String thread,
			String post, String originalName, int width, int height, int size) {}
	public record Snapshot(String chan, List<Item> items) {}

	private static void writeString(DataOutputStream out, String value) throws IOException {
		if (value != null && value.length() > MAX_STRING) throw new IOException("Descriptor too long");
		out.writeBoolean(value != null);
		if (value != null) out.writeUTF(value);
	}
	private static String readString(DataInputStream in) throws IOException {
		String value = in.readBoolean() ? in.readUTF() : null;
		if (value != null && value.length() > MAX_STRING) throw new IOException("Descriptor too long");
		return value;
	}
	public static void write(OutputStream output, Snapshot snapshot) throws IOException {
		if (snapshot.items().isEmpty() || snapshot.items().size() > MAX_ITEMS) throw new IOException("Invalid count");
		DataOutputStream out = new DataOutputStream(output);
		out.writeInt(MAGIC);
		writeString(out, snapshot.chan());
		out.writeInt(snapshot.items().size());
		for (Item item : snapshot.items()) {
			if (item.uri() == null) throw new IOException("Missing attachment");
			writeString(out, item.uri()); writeString(out, item.thumbnail()); writeString(out, item.fileName());
			writeString(out, item.board()); writeString(out, item.thread()); writeString(out, item.post());
			writeString(out, item.originalName());
			out.writeInt(item.width()); out.writeInt(item.height()); out.writeInt(item.size());
			if (out.size() > MAX_BYTES) throw new IOException("Snapshot too large");
		}
		out.flush();
	}
	/** Caller limits file size before reading; count and individual strings are bounded here as well. */
	public static Snapshot read(InputStream input) throws IOException {
		return read(input, () -> false);
	}
	public static Snapshot read(InputStream input, java.util.function.BooleanSupplier cancelled) throws IOException {
		DataInputStream in = new DataInputStream(input);
		if (in.readInt() != MAGIC) throw new IOException("Unknown snapshot format");
		String chan = readString(in);
		int count = in.readInt();
		if (count <= 0 || count > MAX_ITEMS) throw new IOException("Invalid count");
		List<Item> items = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
			Item item = new Item(readString(in), readString(in), readString(in), readString(in),
					readString(in), readString(in), readString(in), in.readInt(), in.readInt(), in.readInt());
			if (item.uri() == null) throw new IOException("Missing attachment");
			items.add(item);
		}
		if (in.read() != -1) throw new IOException("Unexpected trailing data");
		return new Snapshot(chan, List.copyOf(items));
	}
	public static boolean validToken(String token) {
		return token != null && token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
	}
	public static int find(List<Item> items, Item selected, int fallback) {
		if (selected != null) for (int i = 0; i < items.size(); i++) {
			Item item = items.get(i);
			if (Objects.equals(item.uri(), selected.uri()) && Objects.equals(item.post(), selected.post())
					&& Objects.equals(item.board(), selected.board()) && Objects.equals(item.thread(), selected.thread())) return i;
		}
		return items.isEmpty() ? -1 : Math.max(0, Math.min(fallback, items.size() - 1));
	}
}
