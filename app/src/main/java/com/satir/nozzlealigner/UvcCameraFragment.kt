package com.satir.nozzlealigner

import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import com.jiangdg.ausbc.base.CameraFragment

/**
 * libausbc (jiangdg AndroidUSBCamera 3.3.x) tabanlı UVC kamera parçası.
 * Her önizleme karesini NozzleProcessor'a verir ve sonucu MainActivity'e iletir.
 *
 * NOT: libausbc sürüm API'si zamanla değişebilir; bu dosya 3.3.3 içindir.
 * Derlemede getCameraView/getCameraViewContainer imzalarında uyarı olursa
 * kütüphanenin o sürümdeki örneğine göre küçük düzeltme gerekebilir.
 */
class UvcCameraFragment : CameraFragment(), IPreviewDataCallBack {

    var onResult: ((NozzleProcessor.Result) -> Unit)? = null
    var processor: NozzleProcessor? = null      // MainActivity tarafından paylaşılır

    private lateinit var cameraContainer: FrameLayout
    @Volatile private var busy = false

    override fun getCameraView(): IAspectRatio {
        return AspectRatioTextureView(requireContext())
    }

    override fun getCameraViewContainer(): ViewGroup {
        cameraContainer = FrameLayout(requireContext())
        return cameraContainer
    }

    // Çözünürlük/format kütüphane varsayılanıyla gelir. Özel ayar gerekirse
    // libausbc'nin getCameraRequest() override'ı bu sürümde eklenebilir (README'ye bak).

    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                // Ham kare akışını başlat
                getCurrentCamera()?.addPreviewDataCallBack(this)
            }
            ICameraStateCallBack.State.ERROR ->
                Log.e(TAG, "Kamera hatası: $msg")
            else -> {}
        }
    }

    override fun onPreviewData(
        data: ByteArray?,
        width: Int,
        height: Int,
        format: IPreviewDataCallBack.DataFormat
    ) {
        val proc = processor
        if (data == null || proc == null || busy) return
        if (format != IPreviewDataCallBack.DataFormat.NV21) return   // NV21 bekliyoruz
        busy = true
        try {
            val r = proc.process(data, width, height, 0)
            onResult?.invoke(r)
        } catch (t: Throwable) {
            Log.e(TAG, "İşleme hatası", t)
        } finally {
            busy = false
        }
    }

    companion object { private const val TAG = "UvcCameraFragment" }
}
