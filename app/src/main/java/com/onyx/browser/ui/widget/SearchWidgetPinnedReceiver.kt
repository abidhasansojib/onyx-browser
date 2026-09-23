package com.onyx.browser.ui.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.onyx.browser.R

class SearchWidgetPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Toast.makeText(context, R.string.search_widget_pinned_success, Toast.LENGTH_SHORT).show()
    }
}
