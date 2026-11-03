package org.fieldnotes.app

import com.google.zxing.*
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.multi.qrcode.QRCodeMultiReader

/** Dense invitations can contain data patterns mistaken for a fourth finder.
 * Retry ZXing's multi-finder detector instead of trusting its first candidate.
 * Both paths perform the full QR checksum decode; trust checks happen afterward. */
internal class InvitationQrReader(private val base:Map<DecodeHintType,*> = emptyMap<DecodeHintType,Any>()):Reader {
    private val single=QRCodeReader()
    private val multiple=QRCodeMultiReader()
    override fun decode(image:BinaryBitmap):Result=decode(image,emptyMap<DecodeHintType,Any>())
    override fun decode(image:BinaryBitmap,hints:Map<DecodeHintType,*>?):Result {
        val options=base+(hints ?: emptyMap())+mapOf(DecodeHintType.TRY_HARDER to true)
        return try { single.decode(image,options) } catch(_:ReaderException) {
            val results=multiple.decodeMultiple(image,options).distinctBy { it.text }
            if(results.size!=1)throw NotFoundException.getNotFoundInstance()
            results.single()
        }
    }
    override fun reset() { single.reset();multiple.reset() }
}
