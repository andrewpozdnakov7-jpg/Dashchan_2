-dontobfuscate
-dontwarn javax.annotation.**
-keep class com.mishiranu.dashchan.** { *; }
-keep class chan.** { *; }

# OpenCV Java wrappers bind to native symbols by their original class and method names.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Some vendor systems register runtime proxy listeners on RecyclerView. Keep the
# public listener dispatch polymorphic so R8 does not specialize it to ItemTouchHelper.
-keep interface androidx.recyclerview.widget.RecyclerView$OnChildAttachStateChangeListener { *; }
-keepclassmembers class androidx.recyclerview.widget.RecyclerView {
    void dispatchChildAttached(android.view.View);
    void dispatchChildDetached(android.view.View);
}
