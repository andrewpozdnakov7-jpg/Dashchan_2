package chan.content;

import java.util.List;

/** Internal URI contract: chan://owner/res/type/name?g=generation.
 * The g token is process-local cache busting; lookup always uses the current resources, not g. */
final class ChanResourceUri {
	private ChanResourceUri() {}

	private static boolean validName(String value, boolean allowDot) {
		if (value == null || value.isEmpty()) return false;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' ||
					c == '_' || allowDot && c == '.')) return false;
		}
		return true;
	}

	static String build(String chanName, String type, String name, long generation) {
		if (chanName == null || chanName.isEmpty() || !validName(type, false) || !validName(name, true)) {
			return null;
		}
		return "chan://" + chanName + "/res/" + type + "/" + name + "?g=" + generation;
	}

	static boolean accepts(String scheme, String authority, String chanName, List<String> path) {
		return "chan".equals(scheme) && chanName != null && !chanName.isEmpty() &&
				(authority == null || authority.isEmpty() || authority.equals(chanName)) &&
				path != null && path.size() == 3 && "res".equals(path.get(0)) &&
				validName(path.get(1), false) && validName(path.get(2), true);
	}
}
