package org.fieldnotes.app

import android.os.Bundle
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.Size
import com.journeyapps.barcodescanner.Decoder
import com.journeyapps.barcodescanner.DecoderFactory
import com.journeyapps.barcodescanner.camera.CameraSettings

/** Local ZXing camera capture. Invitation frames are not saved or uploaded. */
class EnrollmentCaptureActivity : CaptureActivity() {
    private lateinit var captureView:DecoratedBarcodeView
    override fun initializeContent(): DecoratedBarcodeView = super.initializeContent().also { captureView=it }.apply {
        // A dense square invitation must fit inside the decoding crop in portrait
        // too. The stock scanner's short framing rectangle clipped whole codes.
        val size=(minOf(resources.displayMetrics.widthPixels,resources.displayMetrics.heightPixels)*0.9).toInt()
        barcodeView.framingRectSize=Size(size,size)
        barcodeView.cameraSettings=barcodeView.cameraSettings.apply { focusMode=CameraSettings.FocusMode.CONTINUOUS }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // initializeFromIntent installs a default factory during super.onCreate.
        // Replace it afterward so both camera and image import use the same retry.
        captureView.decoderFactory=DecoderFactory { hints -> Decoder(InvitationQrReader(hints)) }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
