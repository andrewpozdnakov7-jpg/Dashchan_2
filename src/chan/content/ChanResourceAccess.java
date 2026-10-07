package chan.content;

import android.net.Uri;

/** Internal application helpers, deliberately not part of the annotated public extension API. */
public final class ChanResourceAccess {
	private ChanResourceAccess() {}

	/** Resolve at the point of use: tasks may retain a Chan that has since been replaced. */
	public static Chan resolveResourceChan(Chan fallback, Uri uri) {
		return ChanResourceUri.resolveCurrent(fallback, fallback != null ? fallback.name : null,
				uri != null ? uri.getAuthority() : null, Chan::get);
	}

	/** Discard old process-local query/fragment metadata without changing network or archive URIs. */
	public static Uri canonicalize(Uri uri) {
		if (uri == null || !ChanConfiguration.SCHEME_CHAN.equals(uri.getScheme()) || !uri.isHierarchical()) {
			return uri;
		}
		String original = uri.toString();
		String canonical = ChanResourceUri.canonicalIdentity(original);
		return canonical.equals(original) ? uri : Uri.parse(canonical);
	}

	/** Never persist this identity or infer an owner from the currently selected UI forum. */
	public static String normalizeResourceUriForContentComparison(Uri uri, String chanName) {
		return ChanResourceUri.contentComparisonIdentity(uri != null ? uri.toString() : null, chanName);
	}
}
