package com.mishiranu.dashchan.ui.posting;

/** Display-only caption. Never use this value for files, drafts, cache keys or upload names. */
final class AttachmentDisplayName {
	private AttachmentDisplayName() {}
	static String withoutExtension(String name) {
		if (name == null) return "";
		// The caller supplies a display name, not a path/URI. Do not reinterpret a
		// malformed provider value as one, or turn a dotfile into an empty caption.
		if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return name;
		int dot = name.lastIndexOf('.');
		return dot > 0 && dot < name.length() - 1 ? name.substring(0, dot) : name;
	}
}
