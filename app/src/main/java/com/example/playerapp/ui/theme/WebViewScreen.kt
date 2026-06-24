package com.example.playerapp.ui

import android.content.Context
import android.webkit.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun WebViewScreen(
    onShowModal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                }
                
                // Add JavaScript interface for communication
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun showModal() {
                        onShowModal()
                    }
                }, "Android")
                
                // Load the HTML file
                loadUrl("file:///android_asset/control_panel.html")
            }
        },
        modifier = modifier.fillMaxSize()
    )
}

