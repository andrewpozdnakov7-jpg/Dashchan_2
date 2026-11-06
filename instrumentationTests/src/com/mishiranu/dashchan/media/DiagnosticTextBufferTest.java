package com.mishiranu.dashchan.media;

import static org.junit.Assert.*;
import java.io.StringWriter;
import org.junit.Test;

public class DiagnosticTextBufferTest {
	@Test public void ringReusesItsBudgetAndKeepsInitialContextAndLatestWholeEvents() throws Exception {
		int budget = 2 * DiagnosticTextBuffer.CHUNK_CHARS * 2;
		DiagnosticTextBuffer buffer = new DiagnosticTextBuffer(budget);
		assertEquals(0, buffer.allocatedBytes());
		buffer.append("capture_started=true\n");
		String padding = new String(new char[1000]).replace('\0', 'x');
		for (int i = 0; i < 300; i++) {
			buffer.append("event=" + i + " " + padding + "\n");
			assertTrue(buffer.allocatedBytes() <= budget);
		}
		StringWriter output = new StringWriter();
		buffer.writeTo(output);
		String report = output.toString();
		assertTrue(buffer.hasEvicted());
		assertTrue(report.startsWith("ui_buffer_allocation_failures=0 dropped_ui_lines=0\n"));
		assertTrue(report.contains("\ncapture_started=true\n"));
		assertTrue(report.contains("[older_ui_events_evicted]\n"));
		assertFalse(report.contains("event=100 "));
		assertTrue(report.endsWith("event=299 " + padding + "\n"));
		// Every retained event remains a whole line, even across chunk reuse.
		for (String line : report.split("\n")) if (line.startsWith("event=")) {
			assertTrue(line.endsWith(" " + padding));
		}
	}

	@Test public void oversizedLineCannotGrowTheChunkAndRepeatedExportDoesNotMutateIt() throws Exception {
		DiagnosticTextBuffer buffer = new DiagnosticTextBuffer(4 * 1024 * 1024);
		String longLine = new String(new char[DiagnosticTextBuffer.CHUNK_CHARS * 3]).replace('\0', 'z');
		buffer.append(longLine);
		buffer.append("next_event=true\n");
		assertEquals(DiagnosticTextBuffer.CHUNK_CHARS * 2, buffer.allocatedBytes());
		StringWriter first = new StringWriter(), second = new StringWriter();
		buffer.writeTo(first);
		buffer.writeTo(second);
		assertEquals(first.toString(), second.toString());
		assertTrue(first.toString().endsWith(" [line_truncated]\nnext_event=true\n"));
		assertFalse(buffer.hasEvicted());
	}
}
