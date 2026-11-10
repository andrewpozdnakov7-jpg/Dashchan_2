package com.mishiranu.dashchan.ui.gallery;

import android.view.View;
import android.view.ViewGroup;
import com.mishiranu.dashchan.widget.AttachmentView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/** Opening-window targets only. No bitmaps, scrolling, or ownership of the origin hierarchy. */
final class GalleryReturnTargets {
	private final WeakReference<View> root;
	private final ArrayList<Entry> entries = new ArrayList<>();
	private static final class Entry {
		final WeakReference<AttachmentView> view;
		final String resource;
		final long binding;
		Entry(AttachmentView view, String resource) {
			this.view = new WeakReference<>(view); this.resource = resource;
			binding = view.getImageBindingGeneration();
		}
	}
	GalleryReturnTargets(AttachmentView source, String initialResource) {
		View origin = source.getRootView(); root = new WeakReference<>(origin);
		// Prefer the actual opening cell if a resource appears more than once in the origin.
		if (initialResource != null) entries.add(new Entry(source, initialResource));
		collect(origin, source);
	}
	private void collect(View view, AttachmentView source) {
		if (view instanceof AttachmentView && view != source) {
			AttachmentView attachment = (AttachmentView) view;
			if (attachment.getMediaResource() != null) entries.add(new Entry(attachment, attachment.getMediaResource()));
		} else if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), source);
		}
	}
	AttachmentView resolve(String resource) {
		View origin = root.get();
		if (resource == null || origin == null) return null;
		for (Entry entry : entries) {
			if (!resource.equals(entry.resource)) continue;
			AttachmentView view = entry.view.get();
			if (view != null && view.getRootView() == origin && view.isAttachedToWindow() && view.isShown()
					&& view.getImageBindingGeneration() == entry.binding
					&& (view.getMediaResource() == null || resource.equals(view.getMediaResource()))) return view;
		}
		return null;
	}
}
