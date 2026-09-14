package com.firevision.app

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import kotlin.concurrent.thread

data class CropRegion(
    val viewId: Int,
    val label: String,
    val leftRel: Double,
    val topRel: Double,
    val rightRel: Double,
    val bottomRel: Double
)

class MainActivity : AppCompatActivity() {

    private val ipAddress = "192.168.178.22"
    private val port = 5900

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            loadData()
        }

        loadData()
    }

    private fun loadData() {
        val txtStatus = findViewById<TextView>(R.id.txtStatus)
        txtStatus.text = "Verbinde mit Heizung..."

        thread {
            try {
                val bitmap = fetchVncScreenshot(ipAddress, port)
                if (bitmap != null) {
                    runOnUiThread { txtStatus.text = "Lese Werte per OCR aus..." }
                    processImage(bitmap)
                } else {
                    runOnUiThread { txtStatus.text = "Fehler: Kein Bild empfangen" }
                }
            } catch (e: Exception) {
                runOnUiThread { txtStatus.text = "Fehler: ${e.message}" }
            }
        }
    }

    private fun fetchVncScreenshot(host: String, port: Int): Bitmap? {
        val socket = Socket(host, port)
        socket.soTimeout = 6000
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())

        val verBuf = ByteArray(12)
        input.readFully(verBuf)
        output.write("RFB 003.008\n".toByteArray())
        output.flush()

        val secTypesCount = input.readByte().toInt()
        if (secTypesCount > 0) {
            val secTypes = ByteArray(secTypesCount)
            input.readFully(secTypes)
            output.writeByte(1)
            output.flush()
        }

        input.readInt() // SecResult
        output.writeByte(1) // Shared
        output.flush()

        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        
        val pixelFormat = ByteArray(16)
        input.readFully(pixelFormat)
        val nameLen = input.readInt()
        val nameBuf = ByteArray(nameLen)
        input.readFully(nameBuf)

        // Request Full Frame
        output.writeByte(3)
        output.writeByte(0)
        output.writeShort(0)
        output.writeShort(0)
        output.writeShort(width)
        output.writeShort(height)
        output.flush()

        input.readByte() // MsgType
        input.readByte() // Padding
        val rectsCount = input.readUnsignedShort()

        if (rectsCount > 0) {
            input.readUnsignedShort()
            input.readUnsignedShort()
            val rw = input.readUnsignedShort()
            val rh = input.readUnsignedShort()
            val encoding = input.readInt()

            if (encoding == 0) { // RAW
                val pixels = ByteArray(rw * rh * 4)
                input.readFully(pixels)

                val colors = IntArray(rw * rh)
                for (i in 0 until (rw * rh)) {
                    val b = pixels[i * 4].toInt() and 0xFF
                    val g = pixels[i * 4 + 1].toInt() and 0xFF
                    val r = pixels[i * 4 + 2].toInt() and 0xFF
                    colors[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                socket.close()
                return Bitmap.createBitmap(colors, rw, rh, Bitmap.Config.ARGB_8888)
            }
        }
        socket.close()
        return null
    }

    private fun processImage(fullBitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val w = fullBitmap.width
        val h = fullBitmap.height

        val regions = listOf(
            CropRegion(R.id.txtKessel, "", 0.46, 0.39, 0.57, 0.52),
            CropRegion(R.id.txtPufferOben, "Puffer Oben: ", 0.33, 0.38, 0.41, 0.45),
            CropRegion(R.id.txtPufferUnten, "Puffer Unten: ", 0.33, 0.51, 0.41, 0.58),
            CropRegion(R.id.txtWarmwasser, "Warmwasser: ", 0.28, 0.28, 0.38, 0.35)
        )

        for (r in regions) {
            val x = (r.leftRel * w).toInt().coerceAtLeast(0)
            val y = (r.topRel * h).toInt().coerceAtLeast(0)
            val cropW = ((r.rightRel - r.leftRel) * w).toInt().coerceAtMost(w - x)
            val cropH = ((r.bottomRel - r.topRel) * h).toInt().coerceAtMost(h - y)

            if (cropW > 0 && cropH > 0) {
                val cropped = Bitmap.createBitmap(fullBitmap, x, y, cropW, cropH)
                val image = InputImage.fromBitmap(cropped, 0)

                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val digits = visionText.text.filter { it.isDigit() }
                        if (digits.isNotEmpty()) {
                            val text = if (r.label.isEmpty()) "$digits °C" else "${r.label}$digits °C"
                            findViewById<TextView>(r.viewId).text = text
                        }
                    }
            }
        }

        runOnUiThread {
            findViewById<TextView>(R.id.txtStatus).text = "Aktualisiert"
        }
    }
}
