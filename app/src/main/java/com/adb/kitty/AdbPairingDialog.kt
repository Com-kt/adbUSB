/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.adb.kitty.databinding.DialogAdbPairingBinding

class AdbPairingDialog : DialogFragment() {

    private var _binding: DialogAdbPairingBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 赋予标准弹窗样式
        setStyle(STYLE_NORMAL, android.R.style.Theme_Material_Light_Dialog_Alert)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogAdbPairingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvPairingStatus.text = "💡 请在目标设备的开发者选项中点击“使用配对码配对”，将参数填入上方。"
        binding.btnCancel.setOnClickListener { dismiss() }

        binding.btnStartPair.setOnClickListener {
            val ipOrDomain = binding.etIp.text.toString().trim()
            val portStr = binding.etPort.text.toString().trim()
            val code = binding.etPairingCode.text.toString().trim()

            if (ipOrDomain.isEmpty() || portStr.isEmpty() || code.length != 6) {
                binding.tvPairingStatus.text = "❌ 校验失败：参数缺一不可"
                binding.tvPairingStatus.setTextColor(Color.RED)
                return@setOnClickListener
            }

            // 1. 物理锁定 UI 控件
            setUiEnabled(false)

            // 2. 🌟 核心：将收到的数据打包，通过 Fragment Result 通信机制空投给 MainActivity
            setFragmentResult("request_pairing_action", bundleOf(
                "targetHost" to ipOrDomain,
                "port" to portStr.toInt(),
                "code" to code
            ))
        }
    }

    /**
     * 🪐 供 MainActivity 调用的状态看板刷新接口
     */
    fun updateStatus(message: String, color: Int, isEnded: Boolean = false) {
        if (_binding == null) return
        binding.tvPairingStatus.text = message
        binding.tvPairingStatus.setTextColor(color)
        if (isEnded) {
            setUiEnabled(true)
        }
    }

    private fun setUiEnabled(enabled: Boolean) {
        binding.btnStartPair.isEnabled = enabled
        binding.etIp.isEnabled = enabled
        binding.etPort.isEnabled = enabled
        binding.etPairingCode.isEnabled = enabled
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // 🌟 必须置空防止内存泄露
    }
}
