package org.fieldnotes.domain
import kotlin.test.*
import kotlinx.serialization.json.*

class AppUpdateTest {
 private fun manifest()=obj("available" to JsonPrimitive(true),"package" to "org.fieldnotes.app".j(),"protocol" to 1.j(),"size" to 524305.j(),"chunk_size" to 262144.j(),"artifact" to obj("version" to "0.3.2".j(),"version_code" to 5.j(),"sha256" to "a".repeat(64).j(),"signer_sha256" to "b".repeat(64).j(),"architecture" to "arm64-v8a".j()))
 @Test fun pairedOfferHasBoundedChunksAndSupportsNoAvailableBundle(){
  val offer=UpdateOffer.read(manifest())!!;assertEquals(3,offer.chunks);assertEquals(524305L,offer.size)
  assertNull(UpdateOffer.read(obj("available" to JsonPrimitive(false))))
 }
 @Test fun malformedHugeForeignAndOverflowingManifestsAreRejected(){
  for((key,value) in listOf("package" to "other.app".j(),"protocol" to 2.j(),"size" to 0.j(),"size" to 157286401.j(),"chunk_size" to 4295229440L.j()))assertFails{UpdateOffer.read(JsonObject(manifest()+ (key to value)))}
  for((key,value) in listOf("version_code" to 0.j(),"sha256" to "invalid".j(),"architecture" to "x86_64".j())){
   val base=manifest();assertFails{UpdateOffer.read(JsonObject(base+("artifact" to JsonObject(base["artifact"]!!.jsonObject+(key to value)))))}
  }
 }
}
