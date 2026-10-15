package hr.mojatv.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import hr.mojatv.app.data.Prefs

/** Opens the app when the TV stick boots, if enabled in settings. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs(context).autoStart) return
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_FROM_BOOT, true)
        )
    }
}
