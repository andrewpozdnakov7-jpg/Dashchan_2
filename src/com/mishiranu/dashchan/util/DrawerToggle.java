package com.mishiranu.dashchan.util;

import android.animation.ValueAnimator;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.MenuItem;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.core.view.GravityCompat;
import androidx.customview.widget.ViewDragHelper;
import androidx.drawerlayout.widget.DrawerLayout;
import com.mishiranu.dashchan.graphics.BaseDrawable;
import java.lang.reflect.Field;

public class DrawerToggle implements DrawerLayout.DrawerListener {
	private final Activity activity;
	private final DrawerLayout drawerLayout;

	private final ArrowDrawable arrowDrawable;

	public enum Mode {DISABLED, DRAWER, UP}

	private Mode mode = Mode.DISABLED;

	public DrawerToggle(Activity activity, Context toolbarContext, DrawerLayout drawerLayout) {
		this.activity = activity;
		this.drawerLayout = drawerLayout;
		Context context = toolbarContext != null ? toolbarContext : activity;
		arrowDrawable = new ArrowDrawable(context);
	}

	private static final int DRAWER_CLOSE_DURATION;

	static {
		int duration;
		try {
			Field baseSettleDurationField = ViewDragHelper.class.getDeclaredField("BASE_SETTLE_DURATION");
			baseSettleDurationField.setAccessible(true);
			duration = (int) baseSettleDurationField.get(null);
		} catch (Exception e) {
			// Library method, fix if needed
			throw new RuntimeException(e);
		}
		DRAWER_CLOSE_DURATION = duration;
	}

	public void setDrawerIndicatorMode(Mode mode) {
		if (this.mode != mode) {
			this.mode = mode;
			ActionBar actionBar = activity.getActionBar();
			if (mode == Mode.DISABLED) {
				actionBar.setHomeAsUpIndicator(null);
				actionBar.setDisplayHomeAsUpEnabled(false);
			} else {
				actionBar.setDisplayHomeAsUpEnabled(true);
				actionBar.setHomeAsUpIndicator(arrowDrawable);
				boolean open = drawerLayout.isDrawerOpen(GravityCompat.START) && arrowDrawable.position == 1f;
				if (!open) {
					ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
					animator.setDuration(DRAWER_CLOSE_DURATION);
					animator.addUpdateListener(new StateArrowAnimatorListener(mode == Mode.DRAWER));
					animator.start();
				}
			}
		}
	}

	public void syncState() {
		if (mode != Mode.DISABLED) {
			arrowDrawable.setPosition(mode == Mode.UP || drawerLayout.isDrawerOpen(GravityCompat.START) ? 1f : 0f);
			activity.getActionBar().setHomeAsUpIndicator(arrowDrawable);
		}
	}

	public boolean onOptionsItemSelected(MenuItem item) {
		if (item != null && item.getItemId() == android.R.id.home) {
			if (drawerLayout.getDrawerLockMode(GravityCompat.START) != DrawerLayout.LOCK_MODE_UNLOCKED) {
				return false;
			}
			if (mode == Mode.DRAWER) {
				if (drawerLayout.isDrawerVisible(GravityCompat.START)) {
					drawerLayout.closeDrawer(GravityCompat.START);
				} else {
					drawerLayout.openDrawer(GravityCompat.START);
				}
				return true;
			} else if (mode == Mode.UP) {
				if (drawerLayout.isDrawerVisible(GravityCompat.START)) {
					drawerLayout.closeDrawer(GravityCompat.START);
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {
		if (mode == Mode.DRAWER) {
			arrowDrawable.setPosition(slideOffset);
		}
	}

	@Override
	public void onDrawerOpened(@NonNull View drawerView) {
		if (mode == Mode.DRAWER) {
			arrowDrawable.setPosition(1f);
		}
	}

	@Override
	public void onDrawerClosed(@NonNull View drawerView) {
		if (mode == Mode.DRAWER) {
			arrowDrawable.setPosition(0f);
		}
	}

	@Override
	public void onDrawerStateChanged(int newState) {}

	private static final float ARROW_HEAD_ANGLE = (float) Math.toRadians(45);

	private class ArrowDrawable extends BaseDrawable {
		private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Path path = new Path();

		private final float barThickness;
		private final float topBottomArrowSize;
		private final float barSize;
		private final float middleArrowSize;
		private final float barGap;
		private final int size;

		private boolean verticalMirror = false;
		private float position;

		public ArrowDrawable(Context context) {
			paint.setAntiAlias(true);
			paint.setColor(ResourceUtils.getColor(context, android.R.attr.textColorPrimary));
			float density = ResourceUtils.obtainDensity(context);
			size = (int) (24f * density);
			barSize = 16f * density;
			topBottomArrowSize = 9.5f * density;
			barThickness = 2f * density;
			barGap = 3f * density;
			middleArrowSize = 13.6f * density;
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeJoin(Paint.Join.ROUND);
			paint.setStrokeCap(Paint.Cap.SQUARE);
			paint.setStrokeWidth(barThickness);
		}

		public void setPosition(float position) {
			position = Math.min(1f, Math.max(0f, position));
			if (position == 1f) {
				verticalMirror = true;
			} else if (position == 0f) {
				verticalMirror = false;
			}
			this.position = position;
			invalidateSelf();
		}

		@Override
		public int getIntrinsicWidth() {
			return size;
		}

		@Override
		public int getIntrinsicHeight() {
			return size;
		}

		@Override
		public void draw(@NonNull Canvas canvas) {
			Rect bounds = getBounds();
			boolean rtl = isLayoutRtl();
			float position = this.position;
			float arrowSize = AnimationUtils.lerp(barSize, topBottomArrowSize, position);
			float middleBarSize = AnimationUtils.lerp(barSize, middleArrowSize, position);
			float middleBarCut = AnimationUtils.lerp(0f, barThickness / 2f, position);
			float rotation = AnimationUtils.lerp(0f, ARROW_HEAD_ANGLE, position);
			float canvasRotate = AnimationUtils.lerp(rtl ? 0f : -180f, rtl ? 180f : 0f, position);
			float topBottomBarOffset = AnimationUtils.lerp(barGap + barThickness, 0f, position);
			path.rewind();
			float arrowEdge = -middleBarSize / 2f + 0.5f;
			path.moveTo(arrowEdge + middleBarCut, 0f);
			path.rLineTo(middleBarSize - middleBarCut, 0f);
			float arrowWidth = Math.round(arrowSize * Math.cos(rotation));
			float arrowHeight = Math.round(arrowSize * Math.sin(rotation));
			path.moveTo(arrowEdge, topBottomBarOffset);
			path.rLineTo(arrowWidth, arrowHeight);
			path.moveTo(arrowEdge, -topBottomBarOffset);
			path.rLineTo(arrowWidth, -arrowHeight);
			path.moveTo(0f, 0f);
			path.close();
			canvas.save();
			canvas.rotate(canvasRotate * ((verticalMirror ^ rtl) ? -1f : 1f), bounds.centerX(), bounds.centerY());
			canvas.translate(bounds.centerX(), bounds.centerY());
			canvas.drawPath(path, paint);
			canvas.restore();
		}
	}

	private class StateArrowAnimatorListener implements ValueAnimator.AnimatorUpdateListener {
		private final boolean enable;

		public StateArrowAnimatorListener(boolean enable) {
			this.enable = enable;
		}

		@Override
		public void onAnimationUpdate(ValueAnimator animation) {
			float value = (float) animation.getAnimatedValue();
			arrowDrawable.setPosition(enable ? 1f - value : value);
		}
	}

	private boolean isLayoutRtl() {
		return activity.getWindow().getDecorView().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
	}
}
