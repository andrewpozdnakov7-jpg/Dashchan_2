package com.mishiranu.dashchan.media.compression;
import static org.junit.Assert.*;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import androidx.test.core.app.ApplicationProvider;
import com.mishiranu.dashchan.BuildConfig;
import com.mishiranu.dashchan.content.Preferences;
import com.mishiranu.dashchan.util.SharedPreferences;
import org.junit.Test;
public class CompressionBoundaryTest {
    @Test public void localExportIsAvailableAndAutomaticCompressionIsOptIn() {
        SharedPreferences preferences = Preferences.PREFERENCES;
        Boolean before = preferences.contains(CompressionSettings.ENABLED)
                ? preferences.getBoolean(CompressionSettings.ENABLED, false) : null;
        try {
            assertTrue(BuildConfig.ENABLE_VIDEO_COMPRESSION);
            preferences.edit().remove(CompressionSettings.ENABLED).close();
            assertFalse(CompressionSettings.enabled());
            preferences.edit().put(CompressionSettings.ENABLED, true).close();
            assertTrue(CompressionSettings.enabled());
        } finally {
            try (SharedPreferences.Editor editor = preferences.edit()) {
                if (before == null) editor.remove(CompressionSettings.ENABLED);
                else editor.put(CompressionSettings.ENABLED, before);
            }
        }
    }

    @Test public void exportServiceRemainsPrivate() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ServiceInfo info = context.getPackageManager().getServiceInfo(
                new ComponentName(context, VideoCompressionService.class), 0);
        assertFalse(info.exported);
        assertTrue((info.getForegroundServiceType() & ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) != 0);
        if (Build.VERSION.SDK_INT >= 35) {
            assertTrue((info.getForegroundServiceType() & ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING) != 0);
        }
    }
}
