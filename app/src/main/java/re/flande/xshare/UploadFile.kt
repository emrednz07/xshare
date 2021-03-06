package re.flande.xshare

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Toast
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.Blob
import com.github.kittinunf.fuel.core.Method

fun uploadFile(context: Context, uploader: Uploader, file: Uri) {
    Log.d(TAG, "uploading")
    val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val clipManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    val notifID = (Math.random() * 1000000000.0).toInt() // FIXME: there's a slim possibility of a collision
    Log.d(TAG, "notifID $notifID")
    Log.d(TAG, "authority ${file.authority} uri $file")

    val blob = blobFromUri(context, file) ?: return

    val nBuilder = Notification.Builder(context)
            .setContentTitle(blob.name)
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
    notifManager.notify(notifID, nBuilder.build())

    var time = SystemClock.uptimeMillis()

    try {
        uploader.validate()
    } catch (e: Exception) {
        notifManager.cancel(notifID)
        nBuilder.setOngoing(false)
                .setProgress(0, 0, false)
                .setContentText(context.resources.getString(R.string.invalid_uploader_settings))
                .setStyle(Notification.BigTextStyle().bigText(e.toString()))
        notifManager.notify(notifID, nBuilder.build())
        return
    }

    var rurl = uploader.RequestURL
    if (!rurl!!.startsWith("http"))
        rurl = "http://" + rurl

    Fuel.upload(rurl, Method.valueOf(uploader.RequestType!!), uploader.Arguments?.toList())
            .timeout(30_000)
            .timeoutRead(30_000)
            .header(uploader.Headers)
            .name { uploader.FileFormName!! }
            .blob { _, _ -> blob }
            .progress { read, total ->
                //Log.v(TAG, "read $read total $total")
                // .progress gets called once with read=$total, total=0 before resolving the hostname for reasons unknown to me
                if (total == 0L)
                    return@progress

                // avoid slowing down the system with excessive notifications
                val curTime = SystemClock.uptimeMillis()
                if (curTime - time < 100)
                    return@progress
                else
                    time = curTime

                val p: Int = (read * 100 / total).toInt()
                nBuilder.setContentInfoN("$p%")
                        .setProgress(100, p, false)
                notifManager.notify(notifID, nBuilder.build())
            }
            .interrupt {
                // unfortunately this function isn't called when the app gets killed, need to find some other way
                notifManager.cancel(notifID)
            }
            .responseString { _, _, (d, err) ->
                nBuilder.setContentInfoN(null)
                        .setProgress(0, 0, false)
                        .setOngoing(false)
                        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                notifManager.cancel(notifID)

                if (err != null || d == null) {
                    nBuilder.setContentText(context.resources.getString(R.string.upload_failed))
                            .setStyle(Notification.BigTextStyle().bigText(err.toString()))
                } else {
                    try {
                        val url = uploader.prepareUrl(d)
                        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        val intent = PendingIntent.getActivity(context, 0, i, 0)
                        nBuilder.setContentText(url)
                                .setStyle(Notification.BigTextStyle().bigText(url))
                                .setContentIntent(intent)

                        if (prefs.getBoolean("autoclip", false))
                            clipManager.primaryClip = ClipData.newPlainText("URL", url)
                    } catch (e: Exception) {
                        val msg = context.resources.getString(R.string.response_text_and_error, d, e.toString())
                        nBuilder.setContentText(context.resources.getString(R.string.response_handling_error))
                                .setStyle(Notification.BigTextStyle().bigText(msg))
                    }
                }

                notifManager.notify(notifID, nBuilder.build())
            }
}

private fun blobFromUri(context: Context, uri: Uri): Blob? {
    context.grantUriPermission(context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

    try {
        val name = uri.getFilename(context)
        context.contentResolver.openFileDescriptor(uri, "r").use { fd ->
            return Blob(name, fd.statSize, { context.contentResolver.openInputStream(uri) })
        }
    } catch (e: Exception) {
        Toast.makeText(context, e.toString(), Toast.LENGTH_LONG).show()
        return null
    }
}
