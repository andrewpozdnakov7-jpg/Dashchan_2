package com.mishiranu.dashchan.media.compression;
import static org.junit.Assert.*;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import androidx.test.core.app.ApplicationProvider;
import com.mishiranu.dashchan.content.Preferences;
import org.junit.Test;
public class CompressionBoundaryTest {
    @Test public void restoredPreferenceCannotEnableExport() {
        boolean before=Preferences.PREFERENCES.getBoolean(CompressionSettings.ENABLED,false);
        try {
            Preferences.PREFERENCES.edit().put(CompressionSettings.ENABLED,true).close();
            assertFalse(CompressionSettings.enabled());
            assertFalse(VideoCompressionService.isBusy());
            assertFalse(VideoCompressionService.start(null,null));
        } finally { Preferences.PREFERENCES.edit().put(CompressionSettings.ENABLED,before).close(); }
    }
    @Test public void noExportServiceIsRegistered() throws Exception {
        Context c=ApplicationProvider.getApplicationContext();
        try {
            c.getPackageManager().getServiceInfo(new ComponentName(c,VideoCompressionService.class),0);
            fail("An export service must not exist in F-Droid");
        } catch (PackageManager.NameNotFoundException expected) {}
    }
}
