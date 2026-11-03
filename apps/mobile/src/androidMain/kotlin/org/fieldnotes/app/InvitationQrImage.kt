package org.fieldnotes.app

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer

/** Scoped image picker fallback. No storage permission, upload or retained image. */
internal fun readInvitationQrImage(resolver: ContentResolver, uri: Uri): String {
    val bytes=resolver.openInputStream(uri)?.use { input ->
        val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {
            val count=input.read(buffer);if(count<0)break
            require(output.size()+count<=12*1024*1024) { "Choose a QR image smaller than 12 MB." }
            output.write(buffer,0,count)
        }
        output.toByteArray()
    } ?: error("That image could not be opened. Choose it again.")
    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    require(bounds.outWidth in 1..16000 && bounds.outHeight in 1..16000) { "Choose a PNG or JPEG image containing the invitation QR." }
    var sample=1
    while(bounds.outWidth/sample>2048 || bounds.outHeight/sample>2048)sample*=2
    val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: error("That image could not be read.")
    var width=bitmap.width;var height=bitmap.height
    var pixels=IntArray(width*height)
    try { bitmap.getPixels(pixels,0,width,0,0,width,height) } finally { bitmap.recycle() }
    val hints=mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER to true)
    repeat(4) {
        val source=RGBLuminanceSource(width,height,pixels)
        for(luminance in listOf(source,source.invert())) {
            val result=runCatching { InvitationQrReader().decode(BinaryBitmap(HybridBinarizer(luminance)),hints).text }.getOrNull()
            if(result!=null) { require(result.length<=20000) { "This QR is too large to be a Field Notes invitation." };return result }
        }
        val rotated=IntArray(pixels.size)
        for(y in 0 until height)for(x in 0 until width)rotated[x*height+(height-1-y)]=pixels[y*width+x]
        pixels=rotated;val old=width;width=height;height=old
    }
    error("No readable QR found. Choose a clear image containing the whole invitation, including its white border.")
}
