package com.mishiranu.dashchan.widget;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ContextThemeWrapper;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.SearchView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mishiranu.dashchan.R;
import com.mishiranu.dashchan.util.OpaqueColors;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Device regression fixtures. Source-only addition: ADDED / NOT RUN. */
@RunWith(AndroidJUnit4.class)
public class TextInputAppearanceTest {

	private static GradientDrawable mark() {
		GradientDrawable mark = new GradientDrawable(); mark.setColor(Color.WHITE); mark.setSize(4, 24); return mark;
	}
	private static int pixel(Drawable drawable) {
		Rect bounds = new Rect(drawable.getBounds()); Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
		try {
			drawable.setBounds(0, 0, 32, 32); drawable.draw(new Canvas(bitmap)); return bitmap.getPixel(16, 16);
		} finally { drawable.setBounds(bounds); bitmap.recycle(); }
	}
	private static EditText input(android.view.View view) {
		if (view instanceof EditText) return (EditText) view;
		if (view instanceof android.view.ViewGroup) {
			android.view.ViewGroup group = (android.view.ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) { EditText found = input(group.getChildAt(i)); if (found != null) return found; }
		}
		return null;
	}

	@Test public void samePaletteIsIdempotentAndDoesNotMutateEditorSemantics() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			EditText view = new EditText(PostingTestContext.create()); view.setText("keep draft"); view.setSelection(2, 6);
			view.setPadding(3, 5, 7, 9); view.setTextCursorDrawable(mark());
			view.setTextSelectHandleLeft(mark()); view.setTextSelectHandleRight(mark()); view.setTextSelectHandle(mark());
			int ime = view.getImeOptions(), type = view.getInputType(); int[] changes = {0}, focus = {0};
			boolean focused = view.hasFocus(); int minimum = view.getMinHeight();
			view.setOnFocusChangeListener((v, hasFocus) -> focus[0]++);
			view.addTextChangedListener(new TextWatcher() {
				@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
				@Override public void onTextChanged(CharSequence s, int start, int before, int count) { changes[0]++; }
				@Override public void afterTextChanged(Editable s) {}
			});
			TextInputAppearance.apply(view, 0xff285eaa, Color.WHITE); Drawable cursor = view.getTextCursorDrawable();
			for (int i = 0; i < 5; i++) TextInputAppearance.apply(view, 0xff285eaa, Color.WHITE);
			assertSame(cursor, view.getTextCursorDrawable()); assertEquals(4, cursor.getIntrinsicWidth()); assertEquals(24, cursor.getIntrinsicHeight());
			assertEquals(pixel(cursor), pixel(view.getTextSelectHandleLeft())); assertEquals(pixel(cursor), pixel(view.getTextSelectHandleRight()));
			assertEquals(pixel(cursor), pixel(view.getTextSelectHandle()));
			assertEquals("keep draft", view.getText().toString()); assertEquals(2, view.getSelectionStart()); assertEquals(6, view.getSelectionEnd());
			assertEquals(0, changes[0]); assertEquals(0, focus[0]); assertEquals(focused, view.hasFocus());
			assertEquals(ime, view.getImeOptions()); assertEquals(type, view.getInputType()); assertEquals(minimum, view.getMinHeight());
			assertEquals(3, view.getPaddingLeft()); assertEquals(5, view.getPaddingTop()); assertEquals(7, view.getPaddingRight()); assertEquals(9, view.getPaddingBottom());
		});
	}

	@Test public void drawableStateIsNotSharedAndGenericPassCannotUndoLocalPalette() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create(); EditText first = new EditText(context), second = new EditText(context);
			Drawable.ConstantState shared = mark().getConstantState(); assertNotNull(shared);
			first.setTextCursorDrawable(shared.newDrawable()); second.setTextCursorDrawable(shared.newDrawable());
			TextInputAppearance.apply(first, 0xff285eaa, Color.WHITE); int color = pixel(first.getTextCursorDrawable());
			assertEquals(Color.WHITE, pixel(second.getTextCursorDrawable()));
			TextInputAppearance.apply(second, 0xffff3333, Color.BLACK);
			assertEquals(color, pixel(first.getTextCursorDrawable())); assertNotEquals(color, pixel(second.getTextCursorDrawable()));
			FrameLayout root = new FrameLayout(context); root.addView(first); TextInputAppearance.applyTree(root);
			assertEquals(color, pixel(first.getTextCursorDrawable()));
			TextInputAppearance.restoreTheme(first);
			int restored = pixel(first.getTextCursorDrawable()); TextInputAppearance.applyTheme(first);
			assertEquals(restored, pixel(first.getTextCursorDrawable()));
		});
	}

	@Test public void unreadableAccentsStayVisibleOnLightDarkAndCustomSurfaces() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			EditText view = new EditText(PostingTestContext.create()); view.setTextCursorDrawable(mark());
			for (int surface : new int[] {Color.WHITE, Color.BLACK, 0xff777777}) {
				for (int accent : new int[] {Color.WHITE, Color.BLACK, 0xff285eaa}) {
					TextInputAppearance.apply(view, accent, surface);
					int color = pixel(view.getTextCursorDrawable()); assertTrue(OpaqueColors.contrast(color, surface) >= 3);
					assertEquals(64, Color.alpha(view.getHighlightColor()));
					assertEquals(color & 0xffffff, view.getHighlightColor() & 0xffffff);
				}
			}
		});
	}

	@Test public void protectedPalettesNullCursorAndLaterCustomReplacementsRemainOwnedByCaller() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = PostingTestContext.create(); EditText protectedInput = new EditText(context);
			Drawable original = protectedInput.getTextCursorDrawable(); int highlight = protectedInput.getHighlightColor();
			TextInputAppearance.preserve(protectedInput); TextInputAppearance.apply(protectedInput, Color.RED, Color.WHITE);
			assertSame(original, protectedInput.getTextCursorDrawable()); assertEquals(highlight, protectedInput.getHighlightColor());
			EditText photo = new EditText(new ContextThemeWrapper(context, R.style.PhotoEditorDialogTheme));
			Drawable photoCursor = photo.getTextCursorDrawable(); int photoHighlight = photo.getHighlightColor();
			TextInputAppearance.apply(photo, Color.RED, Color.WHITE); assertSame(photoCursor, photo.getTextCursorDrawable());
			assertEquals(photoHighlight, photo.getHighlightColor());
			EditText view = new EditText(context); view.setTextCursorDrawable(mark()); TextInputAppearance.apply(view, Color.RED, Color.WHITE);
			Drawable custom = mark(), customHandle = mark(); view.setTextCursorDrawable(custom); view.setTextSelectHandle(customHandle);
			view.setHighlightColor(0x33445566); TextInputAppearance.apply(view, Color.BLUE, Color.BLACK);
			assertSame(custom, view.getTextCursorDrawable()); assertSame(customHandle, view.getTextSelectHandle()); assertEquals(0x33445566, view.getHighlightColor());
			view.setTextCursorDrawable((Drawable) null); TextInputAppearance.apply(view, Color.RED, Color.WHITE); assertNull(view.getTextCursorDrawable());
		});
	}

	@Test public void whiteOverlaySearchUsesReadableCurrentPaletteAndKeepsQueryAcrossInterfaceModes() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
			Context context = new ContextThemeWrapper(PostingTestContext.create(), R.style.Theme_Special_White);
			FrameLayout root = new FrameLayout(context); SearchView search = new SearchView(context); root.addView(search);
			search.setIconified(false); search.setQuery("retained query", false); EditText edit = input(search); assertNotNull(edit);
			edit.setSelection(2, 5); InterfaceAppearance.standardSearch(root, search);
			for (boolean enabled : new boolean[] {true, false, true}) {
				StandardAppearance.resolveSearch(root, enabled);
				int surface = enabled ? StandardAppearance.colors(context).surfaceContainer : ThemeEngine.getTheme(context).primary;
				assertTrue(OpaqueColors.contrast(pixel(edit.getTextCursorDrawable()), surface) >= 3);
				assertEquals("retained query", search.getQuery().toString()); assertEquals(2, edit.getSelectionStart()); assertEquals(5, edit.getSelectionEnd());
			}
		});
	}
}
