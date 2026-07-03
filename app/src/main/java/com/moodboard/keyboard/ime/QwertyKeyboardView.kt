package com.moodboard.keyboard.ime

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.moodboard.keyboard.R

/**
 * A lightweight, fully programmatic QWERTY keyboard.
 *
 * Why programmatic instead of the deprecated android.inputmethodservice.Keyboard?
 * The old KeyboardView API is deprecated and awkward to theme. Building rows of
 * Buttons keeps the view tiny, predictable and easy to maintain, which matches
 * the project's "minimalistic + lightweight" goal.
 */
class QwertyKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    /** Events the keyboard sends up to the IME service. */
    interface Listener {
        fun onChar(text: CharSequence)
        fun onBackspace()
        fun onEnter()
        fun onSpace()
    }

    var listener: Listener? = null

    private var shifted = true        // start with a capital letter
    private var capsLock = false
    private var symbolsMode = false

    private val rowsLetters = listOf(
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm"
    )
    private val rowsSymbols = listOf(
        "1234567890",
        "@#\$_&-+()/",
        "*\"':;!?"
    )

    init {
        orientation = VERTICAL
        val pad = dp(2)
        setPadding(pad, pad, pad, pad)
        build()
    }

    private fun build() {
        removeAllViews()
        val rows = if (symbolsMode) rowsSymbols else rowsLetters

        // Letter / symbol rows
        rows.forEachIndexed { index, row ->
            val rowLayout = newRow()
            // third row gets shift on the left and backspace on the right
            if (index == 2) {
                rowLayout.addView(
                    specialKey(if (symbolsMode) "=\\<" else shiftLabel(), weight = 1.5f) {
                        if (symbolsMode) { /* secondary symbols not needed for v1 */ }
                        else toggleShift()
                    }
                )
            }
            row.forEach { c ->
                val label = displayChar(c)
                rowLayout.addView(letterKey(label) {
                    listener?.onChar(label)
                    consumeShift()
                })
            }
            if (index == 2) {
                rowLayout.addView(
                    specialKey("⌫", weight = 1.5f) { listener?.onBackspace() }
                )
            }
            addView(rowLayout)
        }

        // Bottom row: mode switch, comma, space, period, enter
        val bottom = newRow()
        bottom.addView(specialKey(if (symbolsMode) "ABC" else "?123", weight = 1.6f) {
            symbolsMode = !symbolsMode
            shifted = !symbolsMode && !capsLock
            build()
        })
        bottom.addView(letterKey(",") { listener?.onChar(","); })
        bottom.addView(specialKey(resources.getString(R.string.key_space), weight = 4.5f) {
            listener?.onSpace()
        })
        bottom.addView(letterKey(".") { listener?.onChar("."); })
        bottom.addView(specialKey("⏎", weight = 1.8f) { listener?.onEnter() })
        addView(bottom)
    }

    private fun shiftLabel(): String = when {
        capsLock -> "⇪"
        shifted -> "⬆"
        else -> "⇧"
    }

    private fun displayChar(c: Char): String =
        if (!symbolsMode && (shifted || capsLock)) c.uppercaseChar().toString() else c.toString()

    private fun toggleShift() {
        when {
            capsLock -> { capsLock = false; shifted = false }
            shifted -> { capsLock = true }           // double-ish behaviour: shift -> caps lock
            else -> { shifted = true }
        }
        build()
    }

    private fun consumeShift() {
        if (shifted && !capsLock) {
            shifted = false
            build()
        }
    }

    // ---- view builders ----

    private fun newRow(): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(3)
        }
        return row
    }

    private fun letterKey(label: String, onClick: () -> Unit): Button =
        baseKey(label, 1f, R.drawable.key_bg, 18f, onClick)

    private fun specialKey(label: String, weight: Float, onClick: () -> Unit): Button =
        baseKey(label, weight, R.drawable.key_special_bg, 15f, onClick)

    private fun baseKey(
        label: String,
        weight: Float,
        bg: Int,
        textSp: Float,
        onClick: () -> Unit
    ): Button {
        val b = Button(context)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(ContextCompat.getColor(context, R.color.kb_text))
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        b.typeface = Typeface.DEFAULT
        b.setPadding(0, 0, 0, 0)
        b.gravity = Gravity.CENTER
        b.background = ContextCompat.getDrawable(context, bg)
        b.minWidth = 0
        b.minHeight = 0
        b.stateListAnimator = null
        val lp = LayoutParams(0, LayoutParams.MATCH_PARENT, weight)
        lp.marginStart = dp(2)
        lp.marginEnd = dp(2)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
