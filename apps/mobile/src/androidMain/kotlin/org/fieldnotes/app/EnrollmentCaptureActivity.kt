package org.fieldnotes.app

import android.os.Bundle
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity

/** Local ZXing camera capture. Invitation frames are not saved or uploaded. */
class EnrollmentCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
