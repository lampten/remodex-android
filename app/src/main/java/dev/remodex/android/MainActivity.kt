package dev.remodex.android

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.remodex.android.app.RemodexAndroidApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RemodexAndroidApp()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // With configChanges declared in the manifest, the system delivers
        // fold/unfold, rotation, and resize events here instead of
        // recreating the activity. Compose recomposes automatically via
        // BoxWithConstraints and WindowInfoTracker, so no manual action
        // is needed — this override exists to make the intent explicit
        // and provide a hook for future debugging.
        Log.d(
            "MainActivity",
            "onConfigurationChanged: ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp"
        )
    }
}
