package org.fieldnotes.app
import androidx.test.platform.app.InstrumentationRegistry
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import org.fieldnotes.domain.UpdateOffer
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.fieldnotes.domain.Reply
import kotlinx.coroutines.*

class UpdateDeviceTest {
 @Test fun interruptedTransferResumesAfterControllerRestartAndKeepsPlannerKeys()=runBlocking {
  assumeTrue("Disposable AVD only",InstrumentationRegistry.getArguments().getString("fieldNotesCalendarGallery")=="synthetic-only")
  val app=context.applicationContext as FieldNotesApplication;app.initialized.await()
  val before=app.vault.load();val file=File(context.applicationInfo.sourceDir);val target=offer(file)
  val folder=File(context.cacheDir,"update-resume-fixture");folder.deleteRecursively();val apk=File(folder,"field-notes-update.apk")
  val indexes=mutableListOf<Int>()
  suspend fun part(path:String):Reply {
   val index=path.substringAfterLast('/').toInt();indexes+=index
   val from=index.toLong()*target.chunkSize
   val bytes=ByteArray(minOf(target.chunkSize.toLong(),target.size-from).toInt())
   java.io.RandomAccessFile(file,"r").use {it.seek(from);it.readFully(bytes)}
   return Reply(200,binary=java.util.Base64.getEncoder().encodeToString(bytes))
  }
  try {
   val transfer=UpdateTransfer(app,folder,apk,target.code-1)
   try {transfer.download(target,"same-server-and-user",{}, {path->if(path.endsWith("/1"))error("Synthetic disconnect") else part(path)},{}) ;fail("Disconnect should interrupt download")}catch(e:IllegalStateException){assertTrue(e.message!!.contains("disconnect"))}
   assertEquals(target.chunkSize.toLong(),File(folder,"download.partial").length());assertFalse(apk.exists())
   indexes.clear()
   val restarted=UpdateTransfer(app,folder,apk,target.code-1)
   restarted.download(target,"same-server-and-user",{},::part,{})
   assertEquals(1,indexes.first());assertEquals(target.size,apk.length());assertTrue(restarted.prepare(target,"same-server-and-user"))
   assertEquals(before,app.vault.load())
   assertFalse(restarted.prepare(target,"another-server-or-user"));assertFalse(apk.exists());assertEquals(before,app.vault.load())
  }finally{folder.deleteRecursively()}
 }
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
