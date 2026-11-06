package com.mishiranu.dashchan.media;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayDeque;

/** Bounded UTF-16 payload, allocated gradually; retains initial context and recent whole lines. */
final class DiagnosticTextBuffer {
	static final int CHUNK_CHARS = 32 * 1024;
	static final int MAX_LINE_CHARS = 16 * 1024;
	private final int maxChunks;
	private final ArrayDeque<Chunk> recent = new ArrayDeque<>();
	private Chunk initial;
	private boolean evicted;
	private long allocationFailures, droppedLines;
	private static final class Chunk {
		final char[] text = new char[CHUNK_CHARS];
		int length;
	}
	DiagnosticTextBuffer(int limitBytes) {
		maxChunks = limitBytes / (2 * CHUNK_CHARS);
		if (maxChunks < 2) throw new IllegalArgumentException("Diagnostic buffer needs two chunks");
	}
	private Chunk allocateChunk() {
		try { return new Chunk(); }
		catch (OutOfMemoryError e) { allocationFailures++; return null; }
	}
	void append(String line) {
		if (line.length() > MAX_LINE_CHARS) {
			String marker = " [line_truncated]\n";
			line = line.substring(0, MAX_LINE_CHARS - marker.length()) + marker;
		}
		if (initial == null) {
			initial = allocateChunk();
			if (initial == null) { droppedLines++; return; }
		}
		Chunk chunk = recent.isEmpty() ? initial : recent.peekLast();
		if (chunk.length + line.length() > CHUNK_CHARS) {
			chunk = recent.size() + 1 < maxChunks ? allocateChunk() : null;
			// As in the native journal, reuse old storage if growth fails before the limit.
			if (chunk == null && !recent.isEmpty()) {
				chunk = recent.removeFirst();
				chunk.length = 0;
				evicted = true;
			}
			if (chunk == null) { droppedLines++; return; }
			recent.addLast(chunk);
		}
		line.getChars(0, line.length(), chunk.text, chunk.length);
		chunk.length += line.length();
	}
	boolean hasEvicted() { return evicted; }
	int allocatedBytes() { return initial != null ? (recent.size() + 1) * CHUNK_CHARS * 2 : 0; }
	/** Called only after recording stops; no full-report string or export copy is created. */
	void writeTo(Writer writer) throws IOException {
		writer.write("ui_buffer_allocation_failures=" + allocationFailures + " dropped_ui_lines=" + droppedLines + "\n");
		if (initial != null) writer.write(initial.text, 0, initial.length);
		if (evicted) writer.write("[older_ui_events_evicted]\n");
		for (Chunk chunk : recent) writer.write(chunk.text, 0, chunk.length);
	}
}
