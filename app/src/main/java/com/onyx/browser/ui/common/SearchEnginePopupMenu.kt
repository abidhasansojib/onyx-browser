package com.onyx.browser.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.onyx.browser.R
import com.onyx.browser.data.model.SearchEngine
import com.onyx.browser.databinding.PopupSearchEnginePickerBinding

class SearchEnginePopupMenu(
    private val context: Context,
    private val currentEngine: SearchEngine,
    private val onEngineSelected: (SearchEngine) -> Unit
) {

    private val popupWindow: PopupWindow
    private val binding: PopupSearchEnginePickerBinding

    init {
        binding = PopupSearchEnginePickerBinding.inflate(LayoutInflater.from(context))

        popupWindow = PopupWindow(
            binding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 24f
        }

        populateEngines()
    }

    private fun populateEngines() {
        val container = binding.llSearchEnginesList
        container.removeAllViews()

        val density = context.resources.displayMetrics.density
        val paddingHorizontal = (16 * density).toInt()
        val paddingVertical = (10 * density).toInt()
        val iconSize = (22 * density).toInt()
        val iconMarginEnd = (14 * density).toInt()
        val checkSize = (18 * density).toInt()

        val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
        val allEngines = prefs.getAllSearchEngines()

        for (engine in allEngines) {
            val row = LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
                isClickable = true
                isFocusable = true

                val typedArray = context.obtainStyledAttributes(
                    intArrayOf(android.R.attr.selectableItemBackground)
                )
                background = typedArray.getDrawable(0)
                typedArray.recycle()
            }

            // Engine Icon
            val ivIcon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginEnd = iconMarginEnd
                }
                setImageResource(engine.iconResId)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(ivIcon)

            // Engine Display Name
            val tvName = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = if (engine.keyword.isNotBlank()) "${engine.displayName} (${engine.keyword})" else engine.displayName
                textSize = 14f

                val isSelected = (engine.id == currentEngine.id)
                if (isSelected) {
                    setTextColor(ContextCompat.getColor(context, R.color.primary))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                } else {
                    val typedArray = context.obtainStyledAttributes(
                        intArrayOf(android.R.attr.textColorPrimary)
                    )
                    setTextColor(typedArray.getColor(0, Color.WHITE))
                    typedArray.recycle()
                    typeface = android.graphics.Typeface.DEFAULT
                }
            }
            row.addView(tvName)

            // Checkmark indicator
            if (engine.id == currentEngine.id) {
                val ivCheck = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(checkSize, checkSize)
                    setImageResource(R.drawable.ic_check)
                }
                row.addView(ivCheck)
            }

            // Row click action
            row.setOnClickListener {
                onEngineSelected(engine)
                popupWindow.dismiss()
            }

            container.addView(row)
        }

        // Divider
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt().coerceAtLeast(1)
            ).apply {
                setMargins(0, (4 * density).toInt(), 0, (4 * density).toInt())
            }
            setBackgroundColor(Color.parseColor("#33888888"))
        }
        container.addView(divider)

        // Manage Search Engines option
        val manageRow = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
            isClickable = true
            isFocusable = true

            val typedArray = context.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackground)
            )
            background = typedArray.getDrawable(0)
            typedArray.recycle()

            val ivManageIcon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginEnd = iconMarginEnd
                }
                setImageResource(R.drawable.ic_settings)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setColorFilter(ContextCompat.getColor(context, R.color.primary))
            }
            addView(ivManageIcon)

            val tvManage = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "Manage search engines…"
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.primary))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            addView(tvManage)

            setOnClickListener {
                popupWindow.dismiss()
                val intent = android.content.Intent(context, com.onyx.browser.ui.settings.SearchEngineSettingsActivity::class.java)
                context.startActivity(intent)
            }
        }
        container.addView(manageRow)
    }

    fun show(anchor: View) {
        val density = context.resources.displayMetrics.density
        val yOffset = (4 * density).toInt()
        popupWindow.showAsDropDown(anchor, 0, yOffset)
    }
}
