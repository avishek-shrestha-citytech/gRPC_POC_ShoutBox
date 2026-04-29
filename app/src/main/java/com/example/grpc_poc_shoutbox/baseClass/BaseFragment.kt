package com.example.grpc_poc_shoutbox.baseClass

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import com.example.grpc_poc.utils.DialogUtil
import kotlin.let
import kotlin.text.isNullOrEmpty

abstract class BaseFragment<VB : ViewBinding> : Fragment() {
    protected lateinit var binding: VB
    private var toast: Toast? = null

    abstract fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?): VB

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = inflateBinding(inflater, container)
        return binding.root
    }

    protected fun showMessage(title: String, message: String) {
        DialogUtil.showMessage(requireContext(), title, message)
    }

    protected fun showError(message: String) {
        DialogUtil.showError(requireContext(), message = message)
    }

    protected fun showSuccess(message: String) {
        DialogUtil.showSuccess(requireContext(), message = message)
    }

    protected fun showToast(message: String?) {
        if (message.isNullOrEmpty()) return
        toast?.cancel()
        toast = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT)
        toast?.show()
    }

    protected fun showToast(message: String?, duration: Int) {
        if (message.isNullOrEmpty()) return
        toast?.cancel()
        toast = Toast.makeText(requireContext(), message, duration)
        toast?.show()
    }

    protected fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        view?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
    }

    override fun onDestroy() {
        toast?.cancel()
        toast = null
        super.onDestroy()
    }
}

