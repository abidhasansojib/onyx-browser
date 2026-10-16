package com.onyx.browser.ui.history

import android.content.Context
import com.onyx.browser.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object HistoryDateUtils {

    fun getDayKey(timestamp: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
    }

    fun formatDayGroupTitle(context: Context, timestamp: Long): String {
        val itemCal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val nowCal = Calendar.getInstance()

        val itemYear = itemCal.get(Calendar.YEAR)
        val itemDay = itemCal.get(Calendar.DAY_OF_YEAR)

        val nowYear = nowCal.get(Calendar.YEAR)
        val nowDay = nowCal.get(Calendar.DAY_OF_YEAR)

        val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isToday = (itemYear == nowYear && itemDay == nowDay)
        val isYesterday = (itemYear == yesterdayCal.get(Calendar.YEAR) && itemDay == yesterdayCal.get(Calendar.DAY_OF_YEAR))

        return when {
            isToday -> context.getString(R.string.history_today)
            isYesterday -> context.getString(R.string.history_yesterday)
            itemYear == nowYear -> {
                SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(timestamp))
            }
            else -> {
                SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
            }
        }
    }
}
