package org.fieldnotes.app
import android.media.MediaPlayer
import androidx.test.platform.app.InstrumentationRegistry
import org.fieldnotes.domain.AlertProfile
import org.fieldnotes.domain.reminderTones
import org.junit.Assert.assertTrue
import org.junit.Test
/** Decodes every original lossless sound through Android's actual media stack.
 * Preparation only: no playback, volume changes, or fixture/data reset. */
class BundledSoundDeviceTest {
 @Test fun everyBundledToneAndBoostDecodesOnAndroid(){val context=InstrumentationRegistry.getInstrumentation().targetContext
  for(tone in reminderTones.keys)for(alarm in listOf(false,true))for(level in listOf(25,50,75,100,125,150,200)){
   val p=AlertProfile(sound=tone,volume=level.coerceAtMost(100),gain=if(level>100)level else 100,alarm=alarm)
   val uri=ReminderSounds.sound(context,p)
   // Resolve the exact URI used by notifications and previews. Deflated APK resources
   // throw here before MediaPlayer even reaches the decoder.
   context.contentResolver.openAssetFileDescriptor(uri,"r").use { descriptor->assertTrue("$tone / $alarm / $level is seekable",descriptor!=null&&descriptor.length>0) }
   val player=MediaPlayer();try{player.setDataSource(context,uri);player.prepare();assertTrue("$tone / $alarm / $level",player.duration>0)}finally{player.release()}
  }
 }
}
