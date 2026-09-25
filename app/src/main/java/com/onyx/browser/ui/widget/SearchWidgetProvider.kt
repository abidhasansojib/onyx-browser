package com.onyx.browser.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.onyx.browser.MainActivity
import com.onyx.browser.R
import com.onyx.browser.data.model.SearchEngine
import com.onyx.browser.data.preferences.BrowserPreferences

class SearchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_UPDATE_SEARCH_WIDGET) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, SearchWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            for (widgetId in allWidgetIds) {
                updateAppWidget(context, appWidgetManager, widgetId)
            }
        }
    }

    companion object {
        const val ACTION_UPDATE_SEARCH_WIDGET = "com.onyx.browser.action.UPDATE_SEARCH_WIDGET"

        fun updateAllWidgets(context: Context) {
            try {
                val intent = Intent(context, SearchWidgetProvider::class.java).apply {
                    action = ACTION_UPDATE_SEARCH_WIDGET
                }
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_search_bar)
            val prefs = BrowserPreferences.getInstance(context)
            val searchEngine = prefs.searchEngine

            // Evaluate system dark/light configuration so icon tint matches launcher theme
            val isSystemDark = (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val iconTint = if (isSystemDark) Color.parseColor("#F1F3F4") else Color.parseColor("#3C4043")
            val textColor = if (isSystemDark) Color.parseColor("#E8EAED") else Color.parseColor("#202124")

            // Explicitly set text and color for launcher theme synchronization
            views.setTextViewText(R.id.widget_search_text, context.getString(R.string.search))
            views.setTextColor(R.id.widget_search_text, textColor)

            // 1. Search Engine Icon
            val engineBitmap = getSearchEngineBitmap(context, searchEngine, iconTint)
            views.setImageViewBitmap(R.id.widget_search_engine_icon, engineBitmap)

            // 2. Microphone Icon (tinted with iconTint: crisp white in dark, Google charcoal in light)
            val micBitmap = getThemedVectorBitmap(context, R.drawable.ic_mic, iconTint)
            views.setImageViewBitmap(R.id.widget_btn_mic, micBitmap)

            // 3. Incognito Icon (tinted with iconTint: crisp white in dark, Google charcoal in light)
            val incognitoBitmap = getThemedVectorBitmap(context, R.drawable.ic_incognito, iconTint)
            views.setImageViewBitmap(R.id.widget_btn_incognito, incognitoBitmap)

            // 4. PendingIntent: Search Bar Click (Clicking search pill launches MainActivity into search mode)
            val searchIntent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_WIDGET_SEARCH
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val searchPendingIntent = PendingIntent.getActivity(
                context,
                1001,
                searchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_search, searchPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_search_bar_container, searchPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_root, searchPendingIntent)

            // 5. PendingIntent: Voice Search Click
            val voiceIntent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_WIDGET_VOICE_SEARCH
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val voicePendingIntent = PendingIntent.getActivity(
                context,
                1002,
                voiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_mic, voicePendingIntent)

            // 6. PendingIntent: Incognito Search Click
            val incognitoIntent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_WIDGET_INCOGNITO_SEARCH
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val incognitoPendingIntent = PendingIntent.getActivity(
                context,
                1003,
                incognitoIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_incognito, incognitoPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun getSearchEngineBitmap(context: Context, engine: SearchEngine, fallbackTint: Int): Bitmap {
            val iconRes = engine.iconResId
            if (iconRes != 0 && iconRes != R.drawable.ic_web) {
                try {
                    val opts = BitmapFactory.Options().apply {
                        inScaled = true
                    }
                    val bmp = BitmapFactory.decodeResource(context.resources, iconRes, opts)
                    if (bmp != null) {
                        val density = context.resources.displayMetrics.density
                        val maxDim = (24 * density).toInt().coerceAtLeast(48)
                        return if (bmp.width > maxDim || bmp.height > maxDim) {
                            Bitmap.createScaledBitmap(bmp, maxDim, maxDim, true)
                        } else {
                            bmp
                        }
                    }
                } catch (_: Exception) {}
            }

            if (iconRes != 0) {
                try {
                    return getThemedVectorBitmap(context, iconRes, fallbackTint)
                } catch (_: Exception) {}
            }

            return createLetterAvatar(context, engine.displayName)
        }

        private fun getThemedVectorBitmap(
            context: Context,
            @DrawableRes drawableRes: Int,
            tintColor: Int
        ): Bitmap {
            val drawable = ContextCompat.getDrawable(context, drawableRes)
                ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val wrapped = DrawableCompat.wrap(drawable.mutate())
            DrawableCompat.setTint(wrapped, tintColor)

            val density = context.resources.displayMetrics.density
            val targetSize = (24 * density).toInt().coerceAtLeast(48)
            val bitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            wrapped.setBounds(0, 0, canvas.width, canvas.height)
            wrapped.draw(canvas)
            return bitmap
        }

        private fun createLetterAvatar(context: Context, name: String): Bitmap {
            val density = context.resources.displayMetrics.density
            val size = (24 * density).toInt().coerceAtLeast(32)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.primary)
            }
            canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size / 4f, size / 4f, bgPaint)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = size * 0.55f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            val letter = if (name.isNotBlank()) name.take(1).uppercase() else "S"
            val yOffset = (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(letter, size / 2f, (size / 2f) - yOffset, textPaint)

            return bitmap
        }
    }
}
