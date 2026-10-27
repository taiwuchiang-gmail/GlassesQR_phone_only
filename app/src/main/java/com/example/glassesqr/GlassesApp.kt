package com.example.glassesqr

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.mockdevice.MockDeviceKit

class GlassesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // MockDeviceKit MUST be enabled BEFORE Wearables.initialize() so the
        // mock stack intercepts registration/permissions/session creation.
        // Verified via search_dat_docs: pairGlasses(RAYBAN_META) + powerOn/don
        // is required before streaming; enable() must precede initialize().
        try {
            MockDeviceKit.getInstance(this).enable()
        } catch (e: Exception) {
            Log.w("GlassesApp", "MockDeviceKit.enable failed (continuing): ${e.message}")
        }
        Wearables.initialize(this)
            .onFailure { error, _ ->
                Log.e("GlassesApp", "Failed to initialize DAT: ${error.description}")
            }
    }
}
