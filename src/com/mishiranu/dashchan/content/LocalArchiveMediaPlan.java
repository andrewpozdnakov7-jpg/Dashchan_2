package com.mishiranu.dashchan.content;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Plans additions without overwriting existing media. Filesystem and ZIP access are provided by the caller. */
public final class LocalArchiveMediaPlan {
	public interface Storage {
		boolean exists(String path);
		boolean complete(String path, long expectedSize);
		String previousSource(String path, long expectedSize);
	}
	public static final class Asset {
		public final String path, source;
		public final long expectedSize, reportedSize;
		public final boolean reused, missing;
		private Asset(String path, String source, long expectedSize, long reportedSize, boolean reused, boolean missing) {
			this.path = path; this.source = source; this.expectedSize = expectedSize;
			this.reportedSize = reportedSize;
			this.reused = reused; this.missing = missing;
		}
		public String name() { return path.substring(path.lastIndexOf('/') + 1); }
	}
	private final String root;
	private final Storage storage;
	private final HashSet<String> occupied = new HashSet<>();
	private final HashMap<String, String> previousBySource = new HashMap<>();
	private final Map<String, Long> verifiedSizes;
	private final LinkedHashMap<String, Asset> assets = new LinkedHashMap<>();
	private final LinkedHashMap<String, String> sources = new LinkedHashMap<>();

	public LocalArchiveMediaPlan(String root, Storage storage, Map<String, String> previousSources,
			Map<String, Long> verifiedSizes, Collection<String> existingPaths) {
		if (root == null || root.isEmpty() || root.equals(".") || root.equals("..")
				|| root.indexOf('/') >= 0 || root.indexOf('\\') >= 0) throw new IllegalArgumentException();
		this.root = root; this.storage = storage; this.verifiedSizes = verifiedSizes;
		for (String path : existingPaths) occupied.add(path.toLowerCase(Locale.ROOT));
		for (Map.Entry<String, String> entry : previousSources.entrySet()) {
			String directory = child(root, "src", entry.getKey()) ? "src"
					: child(root, "thumb", entry.getKey()) ? "thumb" : null;
			if (directory != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
				previousBySource.put(directory + '\0' + entry.getValue(), entry.getKey());
				occupied.add(entry.getKey().toLowerCase(Locale.ROOT));
			}
		}
	}

	public Asset register(String directory, String name, String identity, String remoteSource,
			String previousPath, long reportedSize) {
		if (!("src".equals(directory) || "thumb".equals(directory))) throw new IllegalArgumentException();
		String sourceKey = identity != null ? directory + '\0' + identity : null;
		String path = sourceKey != null ? previousBySource.get(sourceKey) : null;
		if (path == null && child(root, directory, previousPath)) path = previousPath;
		if (path != null) {
			Asset planned = assets.get(path);
			if (planned != null) return planned;
		}
		String oldPath = path;
		Long verifiedSize = path != null ? verifiedSizes.get(path) : null;
		long expectedSize = verifiedSize != null && verifiedSize > 0 ? verifiedSize : 0;
		// Board sizes are display estimates, often rounded KiB. Only a successful-save receipt is exact.
		boolean reused = path != null && expectedSize > 0 && storage.complete(path, expectedSize);
		if (path == null || !reused && storage.exists(path)) path = allocate(directory, name);
		else occupied.add(path.toLowerCase(Locale.ROOT));
		String source = null;
		if (!reused) {
			if (oldPath != null) source = storage.previousSource(oldPath, expectedSize);
			if (source == null) source = remoteSource;
		}
		Asset asset = new Asset(path, source, expectedSize, reportedSize, reused, !reused && source == null);
		assets.put(path, asset);
		if (identity != null && !identity.isEmpty()) {
			sources.put(path, identity);
			previousBySource.put(sourceKey, path);
		}
		return asset;
	}

	private String allocate(String directory, String name) {
		if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
				|| name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) name = "file";
		int extension = name.lastIndexOf('.');
		String base = extension > 0 ? name.substring(0, extension) : name;
		String suffix = extension > 0 ? name.substring(extension) : "";
		String path = root + '/' + directory + '/' + name;
		int count = 1;
		while (!occupied.add(path.toLowerCase(Locale.ROOT))) path = root + '/' + directory + '/' + base + '_' + count++ + suffix;
		return path;
	}

	public Collection<Asset> assets() { return assets.values(); }
	public Map<String, String> sources() { return sources; }
	public static boolean child(String root, String directory, String path) {
		if (path == null || !path.startsWith(root + '/' + directory + '/')) return false;
		String name = path.substring((root + '/' + directory + '/').length());
		return !name.isEmpty() && !name.equals(".") && !name.equals("..") && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
	}
}
