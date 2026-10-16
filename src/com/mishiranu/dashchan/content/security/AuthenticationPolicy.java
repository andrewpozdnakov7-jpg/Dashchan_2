package com.mishiranu.dashchan.content.security;

/** Explicit modes; legacy records retain their previous PIN/optional-biometric behavior. */
public final class AuthenticationPolicy {
	public static final String KEY = "authentication_mode";
	public enum Mode {
		PIN_ONLY("pin", true, false), BIOMETRIC_ONLY("biometric", false, true),
		PIN_OR_BIOMETRIC("pin_or_biometric", true, true);
		public final String value;
		public final boolean pin, biometric;
		Mode(String value, boolean pin, boolean biometric) { this.value = value; this.pin = pin; this.biometric = biometric; }
	}
	private AuthenticationPolicy() {}
	public static Mode read(String value, boolean hasPin, boolean biometric) {
		if (value == null) {
			if (!hasPin && biometric) throw new IllegalArgumentException("Missing authentication configuration");
			return !hasPin ? null : biometric ? Mode.PIN_OR_BIOMETRIC : Mode.PIN_ONLY;
		}
		for (Mode mode : Mode.values()) if (mode.value.equals(value)) {
			if (hasPin != mode.pin || biometric != mode.biometric) throw new IllegalArgumentException("Incomplete authentication mode");
			return mode;
		}
		throw new IllegalArgumentException("Unknown authentication mode");
	}
}
