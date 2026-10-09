package com.mishiranu.dashchan.widget;

import android.view.MenuItem;

public class MenuExpandListener implements MenuItem.OnActionExpandListener {
	public interface Callback {
		boolean onChange(MenuItem menuItem, boolean expand);
	}

	private final Callback callback;

	public MenuExpandListener(Callback callback) {
		this.callback = callback;
	}

	@Override
	public boolean onMenuItemActionExpand(MenuItem menuItem) {
		return change(menuItem, true);
	}

	@Override
	public boolean onMenuItemActionCollapse(MenuItem menuItem) {
		return change(menuItem, false);
	}
	private boolean change(MenuItem menuItem, boolean expand) {
		android.view.View action = menuItem.getActionView();
		ToolbarSearchMotion motion = action instanceof CustomSearchView
				? ToolbarSearchMotion.prepare(((CustomSearchView) action).findToolbar(), expand) : null;
		boolean accepted = false;
		try { accepted = callback.onChange(menuItem, expand); return accepted; }
		finally { if (motion != null) { if (accepted) motion.commit(); else motion.run(); } }
	}
}
