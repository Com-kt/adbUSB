/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.adb.kitty.databinding.DialogAdbPairingBinding

class AdbPairingDialogFragment : DialogFragment() {

    private var _binding: DialogAdbPairingBinding? = null
    private val binding get() = _binding!!

    private var listener: OnPairingListener? = null
    
    // 确保你的项目中有一个叫做 AdbQrPairingServer 的类，如果没有，请确保名字一致
    private var pairingServer: AdbQrPairingServer? = null

    fun setOnPairingListener(listener: OnPairingListener) {
        this.listener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogAdbPairingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 显式造型为你项目实际的 Application 类
        val app = requireActivity().application as MyApp

        binding.tvStatus.text = "正在初始化配对服务..."
        
        pairingServer = AdbQrPairingServer(
            context = requireContext(),
            adbKeyManager = app.adbKeyManager, // 引用 MyApp 中的 adbKeyManager
            onPairingSuccess = {
                listener?.onPairingSuccess()
                dismiss()
            },
            onError = { error: String -> // 显式指定 String 类型，修复 Cannot infer type 报错
                binding.tvStatus.text = "错误: $error"
                binding.pbLoading.visibility = View.GONE
                listener?.onPairingError(error)
            }
        )

        // 调用配对服务的核心方法
        val qrBitmap: Bitmap? = pairingServer?.startPairing()
        if (qrBitmap != null) {
            binding.pbLoading.visibility = View.GONE
            binding.ivQrcode.setImageBitmap(qrBitmap)
            binding.tvStatus.text = "请使用手机“无线调试” -> “扫描二维码配对”"
        } else {
            binding.tvStatus.text = "生成二维码失败"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pairingServer?.stopPairing() // 释放配对 Socket 服务
        _binding = null
    }
}
