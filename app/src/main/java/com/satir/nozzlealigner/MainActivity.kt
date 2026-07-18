package com.satir.nozzlealigner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.satir.nozzlealigner.databinding.ActivityMainBinding
import org.opencv.android.OpenCVLoader
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    // Ortak görüntü işleyici — her iki kaynak da aynı örneği kullanır (kalibrasyon korunur).
    private val processor = NozzleProcessor()

    private var phoneCam: PhoneCameraController? = null
    private var usbFragment: UvcCameraFragment? = null
    private var usbMode = false
    private var lastResult: NozzleProcessor.Result? = null

    private val camPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startPhone()
            else Toast.makeText(this, "Kamera izni gerekli", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, "OpenCV yüklenemedi", Toast.LENGTH_LONG).show()
        }

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Kayıtlı kalibrasyon
        val prefs = getSharedPreferences("cal", MODE_PRIVATE)
        processor.mmPerPx = prefs.getFloat("mmPerPx", 0f).toDouble()
        b.editKnownDia.setText(prefs.getFloat("knownDia", 1.5f).toString())

        // Işık eşiği
        b.seekThreshold.progress = processor.beamThreshold
        b.txtThreshold.text = "Işık eşiği: ${processor.beamThreshold}"
        b.seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                processor.beamThreshold = p
                b.txtThreshold.text = "Işık eşiği: $p"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        b.btnCalibrate.setOnClickListener { calibrate() }
        b.btnInvertX.setOnClickListener {
            processor.invertX = !processor.invertX
            Toast.makeText(this, "X ekseni: ${if (processor.invertX) "ters" else "normal"}", Toast.LENGTH_SHORT).show()
        }
        b.btnInvertY.setOnClickListener {
            processor.invertY = !processor.invertY
            Toast.makeText(this, "Y ekseni: ${if (processor.invertY) "ters" else "normal"}", Toast.LENGTH_SHORT).show()
        }
        b.btnMode.setOnClickListener { toggleMode() }

        // Varsayılan: telefon kamerası (donanım gerektirmez)
        ensurePhoneMode()
    }

    private fun toggleMode() {
        if (usbMode) ensurePhoneMode() else ensureUsbMode()
    }

    private fun ensurePhoneMode() {
        usbMode = false
        b.btnMode.text = "Kaynak: Telefon (ön)"
        b.overlay.mirror = true          // ön kamera aynalı
        // USB fragmentini kaldır
        usbFragment?.let {
            supportFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss()
        }
        usbFragment = null
        b.usbContainer.visibility = View.GONE
        b.previewView.visibility = View.VISIBLE

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) startPhone() else camPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startPhone() {
        if (phoneCam == null) {
            phoneCam = PhoneCameraController(
                this, b.previewView, processor,
                androidx.camera.core.CameraSelector.LENS_FACING_FRONT
            ) { r -> runOnUiThread { render(r) } }
        }
        phoneCam?.start()
    }

    private fun ensureUsbMode() {
        usbMode = true
        b.btnMode.text = "Kaynak: USB"
        b.overlay.mirror = false         // USB kamera aynalanmaz
        phoneCam?.stop()
        b.previewView.visibility = View.GONE
        b.usbContainer.visibility = View.VISIBLE

        val frag = UvcCameraFragment().apply {
            processor = this@MainActivity.processor
            onResult = { r -> runOnUiThread { render(r) } }
        }
        usbFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(R.id.usb_container, frag)
            .commitAllowingStateLoss()
        Toast.makeText(this, "USB kamerayı tak ve izni onayla", Toast.LENGTH_LONG).show()
    }

    private fun render(r: NozzleProcessor.Result) {
        lastResult = r
        b.overlay.update(r)

        val color = when (r.state) {
            NozzleProcessor.State.GREEN -> Color.rgb(0, 200, 0)
            NozzleProcessor.State.YELLOW -> Color.rgb(230, 180, 0)
            NozzleProcessor.State.RED -> Color.rgb(220, 40, 40)
            NozzleProcessor.State.NONE -> Color.GRAY
        }
        b.txtDirection.setTextColor(color)
        b.txtDirection.text = r.direction

        b.txtOffset.text = if (!r.ok) {
            "—"
        } else if (processor.mmPerPx > 0) {
            String.format(Locale.US, "Ofset: %.3f mm  (%.0f px)", r.offsetMm, r.offsetPx)
        } else {
            String.format(Locale.US, "Ofset: %.0f px  (kalibre edilmedi)", r.offsetPx)
        }
    }

    private fun calibrate() {
        val r = lastResult
        if (r == null || !r.ok || r.nozzleRadiusPx <= 0) {
            Toast.makeText(this, "Önce nozul çemberi net görünmeli", Toast.LENGTH_SHORT).show()
            return
        }
        val knownDia = b.editKnownDia.text.toString().replace(',', '.').toFloatOrNull()
        if (knownDia == null || knownDia <= 0f) {
            Toast.makeText(this, "Geçerli nozul çapı (mm) girin", Toast.LENGTH_SHORT).show()
            return
        }
        val mmPerPx = knownDia / (2.0 * r.nozzleRadiusPx)
        processor.mmPerPx = mmPerPx
        getSharedPreferences("cal", MODE_PRIVATE).edit()
            .putFloat("mmPerPx", mmPerPx.toFloat())
            .putFloat("knownDia", knownDia)
            .apply()
        Toast.makeText(this, String.format(Locale.US, "Kalibre edildi: %.5f mm/px", mmPerPx), Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        phoneCam?.release()
        processor.release()
    }
}
