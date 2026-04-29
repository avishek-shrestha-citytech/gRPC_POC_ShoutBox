package com.example.grpc_poc_shoutbox.baseClass

import android.os.Bundle
import android.view.LayoutInflater
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.viewbinding.ViewBinding
import com.example.grpc_poc_shoutbox.utils.DialogUtil

abstract class BaseActivity<VB : ViewBinding> : AppCompatActivity() {
    protected lateinit var binding: VB
    private var toast: Toast? = null

    abstract fun inflateBinding(inflater: LayoutInflater): VB

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = inflateBinding(layoutInflater)
        setContentView(binding.root)
    }

    protected fun showMessage(title: String, message: String) {
        DialogUtil.showMessage(this, title, message)
    }

    protected fun showError(message: String) {
        DialogUtil.showError(this, message = message)
    }

    protected fun showSuccess(message: String) {
        DialogUtil.showSuccess(this, message = message)
    }

    protected fun showToast(message: String?) {
        if (message.isNullOrEmpty()) return
        toast?.cancel()
        toast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        toast?.show()
    }

    protected fun showToast(message: String?, duration: Int) {
        if (message.isNullOrEmpty()) return
        toast?.cancel()
        toast = Toast.makeText(this, message, duration)
        toast?.show()
    }

    protected fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    override fun onDestroy() {
        toast?.cancel()
        toast = null
        super.onDestroy()
    }
}
