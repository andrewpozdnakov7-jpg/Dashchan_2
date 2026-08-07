-dontobfuscate
-dontwarn javax.annotation.**
-keep class com.mishiranu.dashchan.** { *; }
-keep class chan.** { *; }

# ML Kit discovers Firebase component registrars by the class names stored in
# AndroidManifest.xml and creates them through their no-argument constructors.
# Keep both the registrar classes and constructors available for reflection in
# optimized GitHub builds. This is intentionally harmless for the F-Droid
# flavor, where ML Kit is not included.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
	public <init>();
}

# Some vendor systems register runtime proxy listeners on RecyclerView. Keep the
# public listener dispatch polymorphic so R8 does not specialize it to ItemTouchHelper.
-keep interface androidx.recyclerview.widget.RecyclerView$OnChildAttachStateChangeListener { *; }
-keepclassmembers class androidx.recyclerview.widget.RecyclerView {
    void dispatchChildAttached(android.view.View);
    void dispatchChildDetached(android.view.View);
}
