package com.mishiranu.dashchan.content.security;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Synthetic metadata only: never opens application state, SAF or the reset pipeline. */
public class SecurityStateCodecTest {
	@Test public void losingTheModeOrKeyReferenceCannotTurnBiometricOnlyIntoDisabledProtection() throws Exception {
		JSONObject biometric = new JSONObject().put("biometric", true)
				.put(AuthenticationPolicy.KEY, "biometric").put("biometric_alias", "fixture-key");
		assertEquals(AuthenticationPolicy.Mode.BIOMETRIC_ONLY,
				SecurityStore.authenticationMode(SecurityStateCodec.decode(SecurityStateCodec.encode(biometric))));
		JSONObject withoutMode = new JSONObject(biometric.toString()); withoutMode.remove(AuthenticationPolicy.KEY);
		try { SecurityStateCodec.decode(withoutMode.toString().getBytes(StandardCharsets.UTF_8)); fail("Missing mode accepted"); }
		catch (IOException expected) {}
		biometric.remove("biometric_alias");
		try { SecurityStateCodec.encode(biometric); fail("Missing key reference accepted"); }
		catch (IOException expected) {}
	}
	private static String repeat(int length, char value) {
		char[] chars = new char[length]; java.util.Arrays.fill(chars, value); return new String(chars);
	}
	@Test public void exactWriteLimitCanBeReadBackButOneMoreByteIsRejected() throws Exception {
		JSONObject state = new JSONObject().put("fixture", "");
		int overhead = state.toString().getBytes(StandardCharsets.UTF_8).length;
		state.put("fixture", repeat(SecurityStateCodec.MAX_BYTES - overhead, 'a'));
		byte[] bytes = SecurityStateCodec.encode(state);
		assertEquals(SecurityStateCodec.MAX_BYTES, bytes.length);
		assertEquals(state.toString(), SecurityStateCodec.decode(bytes).toString());
		state.put("fixture", state.getString("fixture") + "a");
		try { SecurityStateCodec.encode(state); fail("Oversized output accepted"); } catch (IOException expected) {}
	}
	@Test public void sizeIsMeasuredInUtf8BytesNotCharacters() throws Exception {
		JSONObject state = new JSONObject().put("fixture", repeat(40_000, '\u0430'));
		assertTrue(state.toString().length() < SecurityStateCodec.MAX_BYTES);
		try { SecurityStateCodec.encode(state); fail("UTF-8 size ignored"); } catch (IOException expected) {}
	}
	@Test public void largeLegacyLedgerCompactsWithoutChangingCredentialsOrPendingJournal() throws Exception {
		JSONObject old = new JSONObject().put("primary", new JSONObject().put("verifier", "fixture-primary"))
				.put("additional", new JSONObject().put("verifier", "fixture-additional"))
				.put("reset_pending", true).put("reset_local_done", true).put("reset_tree", "tree-current")
				.put("profile_generation", 7).put("external_cleanup_incomplete", true);
		JSONArray targets = new JSONArray();
		for (int i = 0; i < 12; i++) targets.put("content://fixture/" + repeat(7300, 'x') + i);
		old.put("external_cleanup_targets", targets);
		byte[] bytes = old.toString().getBytes(StandardCharsets.UTF_8);
		assertTrue(bytes.length > SecurityStateCodec.MAX_BYTES);
		JSONObject compact = SecurityStateCodec.decode(bytes);
		assertEquals(old.getJSONObject("primary").toString(), compact.getJSONObject("primary").toString());
		assertEquals(old.getJSONObject("additional").toString(), compact.getJSONObject("additional").toString());
		assertTrue(compact.getBoolean("reset_pending")); assertTrue(compact.getBoolean("reset_local_done"));
		assertEquals("tree-current", compact.getString("reset_tree")); assertEquals(7, compact.getInt("profile_generation"));
		assertFalse(compact.has("external_cleanup_targets"));
		assertEquals(12, compact.getJSONArray("external_cleanup_target_hashes").length());
		assertTrue(SecurityStateCodec.encode(compact).length < 4096);
		ExternalCleanupLedger.cleared(compact, "tree-other"); assertTrue(ExternalCleanupLedger.hasIncomplete(compact));
		for (int i = 0; i < targets.length(); i++) ExternalCleanupLedger.cleared(compact, targets.getString(i));
		assertFalse(ExternalCleanupLedger.hasIncomplete(compact));
	}
	@Test public void overflowingTargetCountKeepsKnownIdentitiesAndDurableUnknownWarning() throws Exception {
		JSONObject state = new JSONObject();
		for (int i = 0; i < 40; i++) ExternalCleanupLedger.skipped(state, "tree-" + i);
		assertEquals(32, state.getJSONArray("external_cleanup_target_hashes").length());
		assertTrue(state.getBoolean("external_cleanup_unknown"));
		for (int i = 0; i < 40; i++) ExternalCleanupLedger.cleared(state, "tree-" + i);
		assertTrue(ExternalCleanupLedger.hasIncomplete(SecurityStateCodec.decode(SecurityStateCodec.encode(state))));
	}
	@Test public void acceptingConsentReservesThePendingDuplicateAndFutureTransitions() throws Exception {
		JSONObject state = new JSONObject().put("primary", "fixture");
		SecurityStateCodec.approveDownloadTarget(state, "tree-small", "fixture folder");
		String before = state.toString();
		try { SecurityStateCodec.approveDownloadTarget(state, repeat(26_000, 'x'), "fixture"); fail("No transition reserve"); }
		catch (IOException expected) {}
		assertEquals(before, state.toString());
	}
	@Test public void unknownOversizedCoreCannotBeRecoveredByDroppingSafetyFields() throws Exception {
		JSONObject state = new JSONObject().put("primary", "fixture").put("reset_pending", true)
				.put("unrecognized", repeat(SecurityStateCodec.MAX_BYTES, 'x'));
		try { SecurityStateCodec.decode(state.toString().getBytes(StandardCharsets.UTF_8)); fail("Oversized core accepted"); }
		catch (IOException expected) {}
		assertTrue(state.getBoolean("reset_pending")); assertEquals("fixture", state.getString("primary"));
	}
}
