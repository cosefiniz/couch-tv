package app.couchtv.helper

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Exists only so Android lets this app see other apps' media sessions (notification access). */
class MediaListener : NotificationListenerService()

/**
 * Commands from the stick, sent over ADB:
 *   am broadcast -f 32 -n app.couchtv.helper/.Cmd -a app.couchtv.helper.INFO
 *   am broadcast -f 32 -n app.couchtv.helper/.Cmd -a app.couchtv.helper.SEEK --el pos 754000
 *   am broadcast -f 32 -n app.couchtv.helper/.Cmd -a app.couchtv.helper.ICON --es pkg com.example.app
 *   am broadcast -f 32 -n app.couchtv.helper/.Cmd -a app.couchtv.helper.CONFIG --es hub <stick ip>:5401
 * The answer is the broadcast's result data (JSON), which `am broadcast` prints.
 */
class Cmd : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val out = try {
            when (i.action) {
                "app.couchtv.helper.INFO" -> info(c, i.getStringExtra("pkg"))
                "app.couchtv.helper.SEEK" -> seek(c, i.getStringExtra("pkg"), i.getLongExtra("pos", -1))
                "app.couchtv.helper.ICON" -> icon(c, i.getStringExtra("pkg") ?: "", i.getIntExtra("size", 128))
                "app.couchtv.helper.CONFIG" -> {
                    val ed = c.getSharedPreferences("helper", 0).edit()
                    i.getStringExtra("hub")?.let { ed.putString("hub", it) }
                    if (i.hasExtra("keep_adb")) ed.putBoolean("keep_adb", i.getBooleanExtra("keep_adb", true))
                    ed.commit()
                    Keeper.kick(c, "config")
                    JSONObject().put("ok", true).put("keep_adb", Keeper.canWrite(c) &&
                        c.getSharedPreferences("helper", 0).getBoolean("keep_adb", true))
                }
                else -> JSONObject().put("ok", false).put("error", "unknown command")
            }
        } catch (e: SecurityException) {
            JSONObject().put("ok", false).put("error", "no notification access")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.toString())
        }
        resultCode = 1
        resultData = out.toString()
    }

    private fun controllers(c: Context): List<MediaController> {
        val msm = c.getSystemService(MediaSessionManager::class.java)
        return msm.getActiveSessions(ComponentName(c, MediaListener::class.java))
    }

    /** The session to talk about: the named app's, else the one playing, else the first paused one. */
    private fun pick(c: Context, pkg: String?): MediaController? {
        val all = controllers(c)
        if (pkg != null) return all.firstOrNull { it.packageName == pkg }
        val st = { m: MediaController -> m.playbackState?.state }
        return all.firstOrNull { st(it) == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull { st(it) == PlaybackState.STATE_BUFFERING }
            ?: all.firstOrNull { st(it) == PlaybackState.STATE_PAUSED }
    }

    private fun info(c: Context, pkg: String?): JSONObject {
        val m = pick(c, pkg) ?: return JSONObject().put("ok", true).put("playing", false)
        val ps = m.playbackState
        val md = m.metadata
        var pos = ps?.position ?: -1L
        if (ps != null && ps.state == PlaybackState.STATE_PLAYING && pos >= 0) {
            pos += ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
        }
        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val state = when (ps?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_BUFFERING -> "buffering"
            PlaybackState.STATE_STOPPED -> "stopped"
            else -> "other"
        }
        return JSONObject()
            .put("ok", true)
            .put("playing", true)
            .put("package", m.packageName)
            .put("state", state)
            .put("position_ms", if (pos >= 0 && dur > 0) minOf(pos, dur) else pos)
            .put("duration_ms", if (dur > 0) dur else JSONObject.NULL)
            .put("can_seek", ps != null && (ps.actions and PlaybackState.ACTION_SEEK_TO) != 0L)
            .put("title", md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.description?.title?.toString() ?: JSONObject.NULL)
    }

    private fun seek(c: Context, pkg: String?, pos: Long): JSONObject {
        if (pos < 0) return JSONObject().put("ok", false).put("error", "pos missing")
        val m = pick(c, pkg) ?: return JSONObject().put("ok", false).put("error", "nothing playing")
        m.transportControls.seekTo(pos)
        return JSONObject().put("ok", true).put("package", m.packageName).put("position_ms", pos)
    }

    private fun icon(c: Context, pkg: String, size: Int): JSONObject {
        val d = c.packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return JSONObject().put("ok", true).put("png", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }
}
