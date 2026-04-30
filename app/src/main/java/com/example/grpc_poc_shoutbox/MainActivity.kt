package com.example.grpc_poc_shoutbox

import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.grpc_poc_shoutbox.baseClass.BaseActivity
import com.example.grpc_poc_shoutbox.databinding.ActivityMainBinding
import com.example.grpc_poc_shoutbox.ui.joinFragment.JoinFragment

class MainActivity : BaseActivity<ActivityMainBinding>() {

    override fun inflateBinding(inflater: LayoutInflater): ActivityMainBinding {
        return ActivityMainBinding.inflate(inflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val insetsTypes = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            val paddingInsets = insets.getInsets(insetsTypes)
            v.setPadding(paddingInsets.left, paddingInsets.top, paddingInsets.right, paddingInsets.bottom)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, JoinFragment())
                .commit()
        }
    }
}