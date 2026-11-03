package org.fieldnotes.app

import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.serialization.json.JsonArray
import org.fieldnotes.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class InvitationQrImageDeviceTest {
    @Test fun offlineImageImportDecodesCompactInvitationAndRejectsBlankImages() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        fun key(n:Int)=Base64.encodeToString(ByteArray(32){n.toByte()},Base64.NO_WRAP)
        val raw=obj("fieldnotes" to 2.j(),"u" to "https://10.82.0.1:8443/api/enrollment".j(),
            "c" to Base64.encodeToString(ByteArray(500){65},Base64.NO_WRAP).j(),"t" to "a".repeat(64).j(),"n" to "Alex".j(),"r" to "member".j(),"e" to (System.currentTimeMillis()/1000+600).j(),
            "w" to JsonArray(listOf("10.82.0.2/32".j(),"home.example:49272".j(),JsonArray(emptyList()),key(2).j(),key(3).j(),"https://10.82.0.1:8443".j(),key(1).j()))).toString()
        val matrix=QRCodeWriter().encode(raw,BarcodeFormat.QR_CODE,800,800)
        val bitmap=Bitmap.createBitmap(800,800,Bitmap.Config.ARGB_8888)
        val pixels=IntArray(800*800) { i -> if(matrix[i%800,i/800])0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        bitmap.setPixels(pixels,0,800,0,0,800,800)
        val file=File(context.cacheDir,"synthetic-qr.png")
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            val decoded=readInvitationQrImage(context.contentResolver,Uri.fromFile(file))
            assertEquals(raw,decoded);assertEquals(key(1),readInvitation(decoded).tunnel!!.text("public_key"))
            bitmap.eraseColor(0xFFFFFFFF.toInt());file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            assertThrows(IllegalStateException::class.java) { readInvitationQrImage(context.contentResolver,Uri.fromFile(file)) }
        }finally{bitmap.recycle();file.delete()}
    }
}
