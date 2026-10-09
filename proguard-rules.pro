-dontobfuscate
-dontwarn javax.annotation.**
# chan.* is the binary API used by separately installed extensions. Retain it
# rather than narrowing it based only on callers visible in this APK.
-keep class chan.** { *; }

# Built-in forum implementations are selected by string names in ChanManager.
-keep class com.mishiranu.dashchan.chan.** { *; }

# Native downcalls use JNI symbol names; upcalls use GetMethodID in player.c.
# The alternate native-engine class loader also reflects INSTANCE and methods.
-keep class com.mishiranu.dashchan.media.GifDecoder { native <methods>; }
-keep class com.mishiranu.dashchan.media.VideoPlayer$Holder { *; }
-keep interface com.mishiranu.dashchan.media.VideoPlayer$HolderInterface { *; }
-keep class com.mishiranu.dashchan.media.VideoPlayer$NativeBridge { *; }

# Restored navigation stores screen class names, not just direct class literals.
-keep class com.mishiranu.dashchan.** extends androidx.fragment.app.Fragment {
	public <init>();
}

# Default ViewModel factories reflect constructors. TaskViewModel.Proxy also
# reads the concrete subclass's generic superclass and creates callback proxies.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep class com.mishiranu.dashchan.** extends androidx.lifecycle.ViewModel {
	public <init>(...);
}
-keep class com.mishiranu.dashchan.content.async.TaskViewModel$Proxy { *; }
-keep interface com.mishiranu.dashchan.**$Callback { *; }

# Parcelable classes are looked up from Parcel/WebViewExtra class names.
-keep class com.mishiranu.dashchan.** implements android.os.Parcelable {
	public static final android.os.Parcelable$Creator CREATOR;
}

# XML inflation cannot see Java-only call sites for custom-view constructors.
-keep class com.mishiranu.dashchan.widget.** extends android.view.View {
	public <init>(android.content.Context, android.util.AttributeSet);
}

# Existing serialized caches must remain readable, including their field layout
# and default serialVersionUID. Do not optimize away migration model members.
-keep class com.mishiranu.dashchan.content.database.PagesDatabase$Legacy$** { *; }

# Google's pure-Java Brotli decoder loads its built-in dictionary by class name.
# Preserve the class and its static data in optimized builds; otherwise Brotli
# responses that use dictionary words fail with "brotli dictionary is not set".
-keep class org.brotli.dec.DictionaryData { *; }
-keepnames class org.brotli.dec.Dictionary

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

# Inflated by TransitionInflater from the GitHub-only motion popup XML.
-keep class com.mishiranu.dashchan.widget.PopupSurfaceTransition {
    public <init>(android.content.Context, android.util.AttributeSet);
}
