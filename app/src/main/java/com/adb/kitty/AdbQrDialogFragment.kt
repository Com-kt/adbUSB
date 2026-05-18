package com.adb.kitty

import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.adb.kitty.databinding.DialogAdbQrBinding
import io.nayuki.qrcodegen.QrCode

class AdbQrDialogFragment : DialogFragment() {

    private var _binding: DialogAdbQrBinding? = null
    private val binding get() = _binding!!

    private var qrText: String? = null
    private var pairingCode: String? = null
    
    // 允许外部监听弹窗关闭事件以销毁后台 Socket
    var onDismissCallback: (() -> Unit)? = null

    companion object {
        fun newInstance(qrText: String, pairingCode: String): AdbQrDialogFragment {
            return AdbQrDialogFragment().apply {
                arguments = Bundle().apply {
                    putString("qr_text", qrText)
                    putString("pairing_code", pairingCode)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            qrText = it.getString("qr_text")
            pairingCode = it.getString("pairing_code")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogAdbQrBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvPairingCode.text = "验证码: $pairingCode"

        qrText?.let { text ->
            // scale = 12 意味每个二维码黑白模块放大为 12x12 像素，兼顾体积与清晰度
            val bitmap = generateQrCodeBitmap(text, scale = 12)
            binding.ivQrCode.setImageBitmap(bitmap)
        }
    }

    override fun onStart() {
        super.onStart()
        // 动态适配屏幕分辨率，宽度设为设备屏幕强占 85%，防止二维码变形拉伸
        dialog?.window?.let { window ->
            val params = window.attributes
            params.width = (resources.displayMetrics.widthPixels * 0.85).toInt()
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            window.attributes = params
        }
    }

    private fun generateQrCodeBitmap(text: String, scale: Int): Bitmap {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val bitmapSize = qr.size * scale
        val bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)

        for (y in 0 until bitmapSize) {
            for (x in 0 until bitmapSize) {
                val color = if (qr.getModule(x / scale, y / scale)) Color.BLACK else Color.WHITE
                bitmap.setPixel(x, y, color)
            }
        }
        return bitmap
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback?.invoke()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // 及时置空，根治 Fragment 内存泄漏问题
    }
}
