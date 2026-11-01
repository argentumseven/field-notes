package org.fieldnotes.domain

import kotlinx.serialization.json.*

/** The paired server selects the APK; repository head alone grants no install authority. */
data class UpdateOffer(val version:String,val code:Long,val sha256:String,val signer:String,val size:Long,val chunkSize:Int,val architecture:String,val source:String?) {
    val chunks:Int get()=((size+chunkSize-1)/chunkSize).toInt()
    companion object {
        fun read(reply:JsonObject):UpdateOffer? {
            if(reply["available"]!=JsonPrimitive(true))return null
            require(reply.text("package")=="org.fieldnotes.app" && reply.number("protocol")==1L){"Unsupported server update format"}
            val a=reply["artifact"]!!.jsonObject
            require(reply.number("chunk_size")==256*1024L){"Unsupported update chunk size"}
            val offer=UpdateOffer(a.text("version"),a.number("version_code"),a.text("sha256"),a.text("signer_sha256"),reply.number("size"),reply.number("chunk_size").toInt(),a.text("architecture"),a["source_commit"]?.jsonPrimitive?.contentOrNull)
            require(offer.version.length<=32 && offer.version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) && offer.code in 1..Int.MAX_VALUE.toLong()){"Invalid update version"}
            require(offer.source==null||offer.source.matches(Regex("[a-f0-9]{40}"))){"Invalid update source"}
            require(offer.sha256.matches(Regex("[a-f0-9]{64}")) && offer.signer.matches(Regex("[a-f0-9]{64}"))){"Invalid update fingerprint"}
            require(offer.size in 1..150L*1024*1024 && offer.chunkSize==256*1024){"Update size is unsupported"}
            require(offer.architecture=="arm64-v8a"){"Unsupported server APK architecture"}
            return offer
        }
    }
}
data class AppUpdateUi(val installed:String="",val offered:String?=null,val available:Boolean=false,val busy:Boolean=false,val progress:Float?=null,val ready:Boolean=false,val message:String="Enroll and sign in to check your paired server.")
