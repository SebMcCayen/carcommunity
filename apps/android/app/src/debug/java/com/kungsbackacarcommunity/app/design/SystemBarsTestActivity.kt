package com.kungsbackacarcommunity.app.design

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

/** Mirrors MainActivity's edge-to-edge setup before its window attaches. */
class SystemBarsTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }
}
