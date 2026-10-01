package org.vedareader

import android.os.Bundle
import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.vedareader.ui.LibraryRoute
import org.vedareader.ui.VedaPreviewTheme
import org.vedareader.reader.ReaderAppearance

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        // Override only locale; copying the entire base configuration freezes pre-window
        // display bounds and can clip accessibility coordinates in edge-to-edge content.
        val configuration = Configuration().apply {
            setLocale(Locale.ENGLISH)
            setLayoutDirection(Locale.ENGLISH)
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor(ReaderAppearance.Header)),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor(ReaderAppearance.Header)),
        )
        setContent {
            VedaPreviewTheme { LibraryRoute() }
        }
    }
}