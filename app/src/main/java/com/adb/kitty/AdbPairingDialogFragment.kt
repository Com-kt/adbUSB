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
    private var pairingServer: AdbQrPairingServer? = null
    
    // 显式声明接收你的 AdbKeyManager
    private var externalKeyManager: AdbKeyManager? = null

    fun setOnPairingListener(listener: OnPairingListener) {
        this.listener = listener
    }

    /**
     * 接收从 MainActivity 传递过来的密钥管理器
     */
    fun setKeyManager(keyManager: AdbKeyManager) {
        this.externalKeyManager = keyManager
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = DialogAdbPairingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvStatus.text = "正在初始化配对服务..."
        
        val finalKeyManager = externalKeyManager
        if (finalKeyManager == null) {
            binding.tvStatus.text = "错误：密钥管理器未就绪"
            binding.pbLoading.visibility = View.GONE
            return
        }

        // 初始化配对服务端
        pairingServer = AdbQrPairingServer(
            context = requireContext(),
            adbKeyManager = finalKeyManager, 
            onPairingSuccess = {
                listener?.onPairingSuccess()
                dismiss()
            },
            onError = { error: String -> 
                binding.tvStatus.text = "错误: $error"
                binding.pbLoading.visibility = View.GONE
                listener?.onPairingError(error)
            }
        )

        // 生成配对二维码
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
        pairingServer?.stopPairing()
        _binding = null
    }
}
