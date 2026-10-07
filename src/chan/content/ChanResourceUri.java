package chan.content;

import java.util.List;
import java.util.function.Function;

/** Stable persistent identity: chan://owner/res/type/name. Generation belongs only to runtime caches. */
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

	static String build(String chanName, String type, String name) {
		if (chanName == null || chanName.isEmpty() || !validName(type, false) || !validName(name, true)) {
			return null;
		}
		return "chan://" + chanName + "/res/" + type + "/" + name;
	}

	static String canonicalIdentity(String uri) {
		if (uri == null || !uri.startsWith("chan://")) return uri;
		int query = uri.indexOf('?');
		int fragment = uri.indexOf('#');
		int end = query < 0 ? fragment : fragment < 0 ? query : Math.min(query, fragment);
		return end < 0 ? uri : uri.substring(0, end);
	}

	/** Comparison only: legacy owner-less resources belong to the owner of the compared thread. */
	static String contentComparisonIdentity(String uri, String chanName) {
		if (uri == null || !uri.startsWith("chan://")) return uri;
		String canonical = canonicalIdentity(uri);
		int pathStart = canonical.indexOf('/', "chan://".length());
		if (pathStart < 0) return uri;
		String[] path = canonical.substring(pathStart).split("/", -1);
		if (path.length != 4 || !"res".equals(path[1]) ||
				!validName(path[2], false) || !validName(path[3], true)) return uri;
		String authority = canonical.substring("chan://".length(), pathStart);
		if (authority.isEmpty() && chanName != null && !chanName.isEmpty()) {
			return "chan://" + chanName + canonical.substring(pathStart);
		}
		return canonical;
	}

	static <T> T resolveCurrent(T fallback, String fallbackName, String authority, Function<String, T> lookup) {
		String owner = authority != null && !authority.isEmpty() ? authority : fallbackName;
		return owner != null && !owner.isEmpty() ? lookup.apply(owner) : fallback;
	}

	static boolean accepts(String scheme, String authority, String chanName, List<String> path) {
		return "chan".equals(scheme) && chanName != null && !chanName.isEmpty() &&
				(authority == null || authority.isEmpty() || authority.equals(chanName)) &&
				path != null && path.size() == 3 && "res".equals(path.get(0)) &&
				validName(path.get(1), false) && validName(path.get(2), true);
	}
}
