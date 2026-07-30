package chan.http;

import android.content.Context;
import com.mishiranu.dashchan.content.MainApplication;
import com.mishiranu.dashchan.content.Preferences;
import java.lang.reflect.Method;

final class SecurityProviderInstaller {
	private SecurityProviderInstaller() {}

	public static void installIfEnabled() {
		if (!Preferences.isUseGmsProvider()) {
			return;
		}
		try {
			// Load GmsCore_OpenSSL from Google Play Services package.
			Context context = MainApplication.getInstance().createPackageContext("com.google.android.gms",
					Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
			Class<?> providerInstallerImplClass = Class.forName("com.google.android.gms.common.security"
					+ ".ProviderInstallerImpl", false, context.getClassLoader());
			Method insertProviderMethod = providerInstallerImplClass.getMethod("insertProvider", Context.class);
			insertProviderMethod.invoke(null, context);
		} catch (Exception e) {
			// The provider is optional; keep the platform provider when it is unavailable.
		}
	}
}
