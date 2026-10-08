package ani.sanin.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName

/**
 * Downloads and the scheduled notification checks both need the process to survive while the device
 * is idle. OEM skins (Infinix/Transsion especially) kill background work regardless of the foreground
 * service unless the app is exempt from battery optimisation, so the first time work is queued the
 * user is sent to the system opt-out for this app.
 */
object BatteryOptimizations {

    fun isUnrestricted(context: Context): Boolean = try {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) {
        false
    }

    /**
     * One-shot prompt. Opens the system dialog that leads to this app's battery settings; if that
     * intent is unavailable on the device it falls back to the app details settings page.
     *
     * Does nothing when we are already unrestricted or when the user has already been asked.
     */
    fun promptIfNeeded(activity: Activity) {
        if (isUnrestricted(activity)) return
        if (PrefManager.getVal(PrefName.BatteryOptimizationPrompted)) return
        PrefManager.setVal(PrefName.BatteryOptimizationPrompted, true)

        val exemption = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}"),
        )
        try {
            activity.startActivity(exemption)
        } catch (_: Exception) {
            Logger.log("Battery optimisation prompt unavailable, opening app settings instead")
            try {
                activity.startActivity(appDetailsSettings(activity))
            } catch (_: Exception) {
                Logger.log("No battery settings screen available")
            }
        }
    }

    private fun appDetailsSettings(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
}
