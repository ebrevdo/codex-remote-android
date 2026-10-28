package com.codex.remote.ui.screens

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.NestedScrollView
import com.codex.remote.ui.theme.MonoText

/** Keep the Compose row finite while the native text layout retains all of its content. */
internal class ScrollableTextView(context: Context) : NestedScrollView(context) {
    val textView = TextView(context).apply {
        setTextIsSelectable(true)
        includeFontPadding = false
    }
    private var contentKey: Any? = null
    private var followOnNextLayout = false

    init {
        addView(textView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun updateContent(key: Any, follow: Boolean, update: (TextView) -> Unit) {
        if (contentKey == key) {
            if (!follow) followOnNextLayout = false
            return
        }
        val atBottom = !canScrollVertically(1)
        val hadContent = contentKey != null
        contentKey = key
        update(textView)
        followOnNextLayout = follow && hadContent && atBottom
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (followOnNextLayout) {
            followOnNextLayout = false
            scrollTo(0, (textView.bottom - height + paddingBottom).coerceAtLeast(0))
        }
    }
}

@Composable
internal fun ScrollableTextBody(
    text: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    followOutput: Boolean = false,
) {
    val style = if (mono) MonoText else MaterialTheme.typography.bodyMedium
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val density = LocalDensity.current
    val fontSize = with(density) { style.fontSize.toPx() }
    val lineHeight = with(density) { style.lineHeight.toPx() }
    AndroidView(
        factory = ::ScrollableTextView,
        update = { view ->
            view.textView.apply {
                setTextColor(color)
                typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
                setLineSpacing(lineHeight - paint.fontSpacing, 1f)
            }
            view.updateContent(text, followOutput) { it.text = text }
        },
        modifier = modifier.fillMaxWidth().heightIn(max = 480.dp),
    )
}
