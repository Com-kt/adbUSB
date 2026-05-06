package com.adb.kitty

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.adb.kitty.databinding.DialogSimpleBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AutoDismissDialogFragment : DialogFragment() {

    // 定义 ViewBinding 变量
    private var _binding: DialogSimpleBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // 使用 inflate 方法初始化
        _binding = DialogSimpleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 直接通过 binding 访问控件
        binding.dialogText.text = "给我扫点，不然没钱流落街头，或者来个人收留我"

        isCancelable = false

        viewLifecycleOwner.lifecycleScope.launch {
            delay(3000)
            if (dialog?.isShowing == true) {
                dismiss()
            }
        }
    }

    // 必须在 onDestroyView 中置空 binding
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
