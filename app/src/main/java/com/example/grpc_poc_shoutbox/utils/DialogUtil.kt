package com.example.grpc_poc_shoutbox.utils

import android.content.Context
import androidx.appcompat.app.AlertDialog

object  DialogUtil {
    fun showMessage(context: Context, title: String, message: String, onDismiss: (() -> Unit)? = null) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                onDismiss?.invoke()
            }
            .show()
    }

    fun showError(context: Context, title: String = "Error", message: String) {
        showMessage(context, title, message)
    }

    fun showSuccess(context: Context, title: String = "Success", message: String) {
        showMessage(context, title, message)
    }
}