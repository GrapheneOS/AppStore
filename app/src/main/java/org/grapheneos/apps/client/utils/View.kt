package org.grapheneos.apps.client.utils

import android.view.View
import androidx.fragment.app.Fragment
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import org.grapheneos.apps.client.R

fun View.showSnackbar(msg: String, isError: Boolean? = null) {
    val view = findViewById(android.R.id.content) ?: this
    val snackbar = Snackbar.make(
        DynamicColors.wrapContextIfAvailable(this.context),
        view,
        msg,
        Snackbar.LENGTH_SHORT
    )
    if (isError == true) {
        snackbar.setTextColor(MaterialColors.getColor(this, R.attr.colorError))
    }
    snackbar.show()
}

fun Fragment.showSnackbar(msg: String, isError: Boolean? = null) = view?.showSnackbar(msg, isError)