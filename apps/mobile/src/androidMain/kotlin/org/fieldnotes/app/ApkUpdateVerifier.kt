package org.fieldnotes.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest
import org.fieldnotes.domain.UpdateOffer

internal object ApkUpdateVerifier {
    @Suppress("DEPRECATION")
    fun installIntent(pm:PackageManager,uri:Uri):Intent {
        val intent=Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val installer=pm.queryIntentActivities(intent,PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY).firstOrNull()?.activityInfo
            ?:error("Android's system package installer is unavailable. Your existing app and data are kept.")
        return intent.setClassName(installer.packageName,installer.name)
    }
    fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it)}
    fun installedSigner(pm:PackageManager,name:String):String {
        @Suppress("DEPRECATION") val info=pm.getPackageInfo(name,if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION") val signatures=if(Build.VERSION.SDK_INT>=28)info.signingInfo!!.apkContentsSigners else info.signatures!!
        check(signatures.size==1){"Multiple APK signers require an operator-reviewed upgrade."}
        return hex(MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray()))
    }
    fun verify(pm:PackageManager,name:String,current:Long,file:File,target:UpdateOffer){
        check(target.code>current){"APK downgrade or same-version replacement blocked."}
        check(file.length()==target.size){"APK length mismatch."}
        val actual=file.inputStream().use{input->val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)};hex(hash.digest())}
        check(actual==target.sha256){"APK checksum mismatch."}
        val verified=ApkVerifier.Builder(file).setMinCheckedPlatformVersion(26).build().verify()
        check(verified.isVerified&&verified.signerCertificates.size==1){"APK signature verification failed. Nothing was installed."}
        val signer=hex(MessageDigest.getInstance("SHA-256").digest(verified.signerCertificates.single().encoded))
        check(signer==target.signer&&signer==installedSigner(pm,name)){"APK signer differs from this installation. Do not uninstall or reset pairing; rebuild the APK with the original signer."}
        @Suppress("DEPRECATION") val archive=pm.getPackageArchiveInfo(file.path,0)?:error("APK package could not be read.")
        val archiveCode=if(Build.VERSION.SDK_INT>=28)archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
        check(archive.packageName==name&&archiveCode==target.code&&archive.versionName==target.version){"APK identity/version does not match the paired server."}
        check(archive.applicationInfo!!.minSdkVersion<=Build.VERSION.SDK_INT){"This update needs a newer Android version."}
        java.util.zip.ZipFile(file).use{zip->check(Build.SUPPORTED_ABIS.any{abi->zip.entries().asSequence().any{it.name.startsWith("lib/$abi/")}}){"APK architecture does not support this device."}}
    }
}
