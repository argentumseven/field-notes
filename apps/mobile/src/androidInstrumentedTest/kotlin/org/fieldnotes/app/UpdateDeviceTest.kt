package org.fieldnotes.app
import androidx.test.platform.app.InstrumentationRegistry
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import org.fieldnotes.domain.UpdateOffer
import org.junit.Test
import org.junit.Assert.*

class UpdateDeviceTest {
 @Test fun brokenSoftwareCacheCannotEraseTheVaultOrPreventOpeningTheUpdater()=kotlinx.coroutines.runBlocking{
  val app=context.applicationContext as FieldNotesApplication;app.initialized.await();val before=app.vault.load()
  val cache=File(context.cacheDir,"updates");cache.deleteRecursively();cache.writeText("synthetic cache failure")
  try{
   val updates=ApkUpdates(app);assertFalse(updates.state.value.ready)
   assertEquals(before,app.vault.load());assertNotNull(updates.state.value.message)
  }finally{cache.delete();cache.mkdirs()}
 }
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private fun offer(file:File):UpdateOffer{
  val info=context.packageManager.getPackageInfo(context.packageName,0)
  val hash=file.inputStream().use{input->val digest=MessageDigest.getInstance("SHA-256");val bytes=ByteArray(65536);while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)};ApkUpdateVerifier.hex(digest.digest())}
  val code=if(Build.VERSION.SDK_INT>=28)info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
  return UpdateOffer(info.versionName!!,code,hash,ApkUpdateVerifier.installedSigner(context.packageManager,context.packageName),file.length(),262144,Build.SUPPORTED_ABIS.first(),null)
 }
 @Test fun actualSignedPackageVerifiesAndDowngradesBadHashesSignersAndIdentityFailClosed(){
  val file=File(context.applicationInfo.sourceDir);val target=offer(file);val pm=context.packageManager
  ApkUpdateVerifier.verify(pm,context.packageName,target.code-1,file,target)
  for(candidate in listOf(target.copy(sha256="0".repeat(64)),target.copy(signer="0".repeat(64)),target.copy(code=target.code+1))){
   try{ApkUpdateVerifier.verify(pm,context.packageName,target.code-1,file,candidate);fail("Invalid update was accepted")}catch(_:IllegalStateException){}
  }
  try{ApkUpdateVerifier.verify(pm,context.packageName,target.code,file,target);fail("Same-version replacement accepted")}catch(_:IllegalStateException){}
  val other=File(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)
  try{ApkUpdateVerifier.verify(pm,context.packageName,target.code-1,other,offer(other));fail("Other package accepted")}catch(_:IllegalStateException){}
 }
 @Test fun updateProviderIsPrivateAndInstallAuthorityRequiresAndroidConsent(){
  @Suppress("DEPRECATION") val info=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS)
  val provider=info.providers!!.single{it.authority=="${context.packageName}.updates"}
  assertFalse(provider.exported);assertTrue(provider.grantUriPermissions)
  assertTrue(info.requestedPermissions!!.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
  val intent=ApkUpdateVerifier.installIntent(context.packageManager,android.net.Uri.parse("content://${context.packageName}.updates/apk_updates/field-notes-update.apk"))
  assertNotNull(intent.component);assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
  assertEquals(0,intent.flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
  @Suppress("DEPRECATION") val installer=context.packageManager.getActivityInfo(intent.component!!,0)
  assertTrue(installer.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM!=0)
 }
}
