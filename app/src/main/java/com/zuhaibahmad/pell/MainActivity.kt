package com.zuhaibahmad.pell

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.kimjio.wear.datetimepicker.widget.TimePicker
import org.joda.time.DateTime
import org.joda.time.Duration
import java.util.concurrent.TimeUnit

const val TAG = "MainActivity"
const val KEY_LAST_SHUTDOWN = "last_shutdown"

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TimePicker>(R.id.vTimePicker)
            .setOnTimeChangedListener { _, hour, minute -> schedule(hour, minute) }
    }

    private fun schedule(hour: Int, minutes: Int) {
        var time = DateTime.now().withTimeAtStartOfDay().plusHours(hour).plusMinutes(minutes)
        if (time.isBefore(DateTime.now())) {
            // If the target time has passed than schedule for the next day
            time = time.plusDays(1)
        }
        val message = "Shutdown scheduled for $time"
        Log.d(TAG, message)

        // Attempt to execute shutdown command on non-rooted devices may result in a crash
        if (!RootUtil.isDeviceRooted()) {
            Toast.makeText(applicationContext, "Device not rooted", Toast.LENGTH_LONG).show()
            return
        }

        val delay = Duration(DateTime.now(), time).standardMinutes
        val workRequest = PeriodicWorkRequest
            .Builder(
                PowerOffWorker::class.java,
                24,
                TimeUnit.HOURS,
                PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .addTag("power_off_worker")
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork(
                "power_off_worker",
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )

        // Display a toast and kill the app once the time is set
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        finish()
    }

    class PowerOffWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        override fun doWork(): Result {
            // Ensure that we are not stuck in a boot loop due to the device shutting down before
            // the worker could post success status.
            val lastShutdownMillis = prefs.getLong(KEY_LAST_SHUTDOWN, 0)
            val lastShutdownTime = DateTime(lastShutdownMillis)
            val isWithin24Hours = lastShutdownTime.isAfter(DateTime.now().minusDays(1))
            if (isWithin24Hours) {
                Log.d(TAG, "A shutdown attempt was made less than a day ago at: $lastShutdownTime")
                return Result.success()
            }

            // Update last attempt time before attempting a shutdown
            return try {
                Log.d(TAG, "Shutting down...")
                prefs.edit()
                    .putLong(KEY_LAST_SHUTDOWN, DateTime.now().millis)
                    .apply()
                Runtime.getRuntime()
                    .exec(arrayOf("su", "-c", "reboot -p"))
                Result.success()
            } catch (ex: Exception) {
                ex.printStackTrace()
                Result.failure()
            }
        }
    }
}