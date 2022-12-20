package app.grapheneos.apps.util

import android.content.Context
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.getSystemService

fun StringBuilder.appendRes(ctx: Context, @StringRes ref: Int) {
    append(ctx.getString(ref))
}

fun TextView.maybeSetText(text: CharSequence) {
    if (text.equals(getText())) {
        return
    }
    setText(text)
}

fun TextView.maybeSetText(text: Int) {
    maybeSetText(context.getText(text))
}

fun MenuItem.setAvailable(v: Boolean) {
    isEnabled = v
    isVisible = v
}

fun requestKeyboard(v: View) {
    v.requestFocus()
    val imm = v.context.getSystemService<InputMethodManager>()!!
    imm.showSoftInput(v, 0)
}
