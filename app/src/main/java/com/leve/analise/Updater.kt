package com.leve.analise

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização automática: confere a última versão publicada no GitHub,
 * avisa quando tem nova e, com um toque, baixa e abre o instalador do Android.
 */
object Updater {
    private const val REPO = "Gabrielbatman266/Analise-leve"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val APK = "https://github.com/$REPO/releases/latest/download/AnaliseLeve.apk"
    const val FILE = "update.apk"
    private const val JOB_ID = 51
    private const val NID = 21

    private fun p(c: Context) = Store.prefs(c)
    fun autoOn(c: Context) = p(c).getBoolean("auto_update", true)
    fun setAuto(c: Context, on: Boolean) { p(c).edit().putBoolean("auto_update", on).apply(); if (on) schedule(c) else cancel(c) }

    fun mine(c: Context): Int = try {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (e: Exception) { 0 }

    fun mineName(c: Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "PostLeve")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    /** Número da última versão publicada (o "N" de v1.0.N), ou -1 se não deu pra ver. */
    fun latest(): Int = try {
        val c = open(API)
        val txt = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        Regex("\"tag_name\"\\s*:\\s*\"[^\"]*?(\\d+)\"").find(txt)?.groupValues?.get(1)?.toInt() ?: -1
    } catch (e: Exception) { -1 }

    // ------------------------------------------------------------------ automático em segundo plano

    fun schedule(c: Context) {
        if (!autoOn(c)) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(c, UpdateJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(6 * 60 * 60 * 1000L)
            .setPersisted(false)
            .build()
        try {
            val js = c.getSystemService(JobScheduler::class.java)
            if (js.allPendingJobs.none { it.id == JOB_ID }) js.schedule(job)
        } catch (e: Exception) { }
    }

    private fun cancel(c: Context) { try { c.getSystemService(JobScheduler::class.java).cancel(JOB_ID) } catch (e: Exception) { } }

    fun notifyNew(c: Context, v: Int) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("update", "Atualizações do app", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(c, 90, Intent(c, MainActivity::class.java).putExtra("update", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, "update")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Post Leve 1.0.$v disponível")
            .setContentText("Toque pra baixar e instalar. Nada do app é perdido.")
            .setContentIntent(pi).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Atualizar", pi).build())
            .build()
        try { nm.notify(NID, n) } catch (e: SecurityException) { }
    }

    /** Ao abrir o app: confere no máximo a cada 3 horas e pergunta se tem versão nova. */
    fun checkOnOpen(a: Activity) {
        if (!autoOn(a)) return
        val last = p(a).getLong("upd_last", 0)
        if (System.currentTimeMillis() - last < 3 * 60 * 60 * 1000L) return
        p(a).edit().putLong("upd_last", System.currentTimeMillis()).apply()
        Thread {
            val v = latest()
            if (v > mine(a) && p(a).getInt("upd_skip", 0) != v) Handler(Looper.getMainLooper()).post { ask(a, v, true) }
        }.start()
    }

    /** Botão "Procurar atualização". */
    fun checkNow(a: Activity) {
        Toast.makeText(a, "Procurando atualização…", Toast.LENGTH_SHORT).show()
        Thread {
            val v = latest()
            Handler(Looper.getMainLooper()).post {
                when {
                    v <= 0 -> Toast.makeText(a, "Não consegui ver no GitHub. Confira a internet.", Toast.LENGTH_LONG).show()
                    v <= mine(a) -> Toast.makeText(a, "Você já tem a versão mais nova (1.0.${mine(a)})", Toast.LENGTH_LONG).show()
                    else -> ask(a, v, false)
                }
            }
        }.start()
    }

    private fun ask(a: Activity, v: Int, canSkip: Boolean) {
        if (a.isFinishing) return
        val b = AlertDialog.Builder(a).setTitle("Atualização disponível")
            .setMessage("Versão nova: 1.0.$v\nVocê tem: 1.0.${mine(a)}\n\nBaixar e instalar agora? Fila, esboços, vídeos e legendas continuam.")
            .setPositiveButton("Atualizar") { _, _ -> download(a) }
            .setNegativeButton("Depois", null)
        if (canSkip) b.setNeutralButton("Pular essa") { _, _ -> p(a).edit().putInt("upd_skip", v).apply() }
        b.show()
    }

    // ------------------------------------------------------------------ baixar e instalar

    fun download(a: Activity) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(a, "Permita \"Instalar apps desconhecidos\" pro Post Leve e toque em Atualizar de novo", Toast.LENGTH_LONG).show()
            try { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))) } catch (e: Exception) { }
            return
        }
        val dlg = AlertDialog.Builder(a).setTitle("Baixando atualização").setMessage("0%").setCancelable(false).show()
        val ui = Handler(Looper.getMainLooper())
        Thread {
            val f = File(a.cacheDir, FILE)
            val ok = try {
                val c = open(APK)
                val total = c.contentLengthLong
                c.inputStream.use { inp ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(16384); var done = 0L; var last = -1
                        while (true) {
                            val n = inp.read(buf); if (n <= 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) { val pct = (done * 100 / total).toInt(); if (pct != last) { last = pct; ui.post { dlg.setMessage("$pct%") } } }
                        }
                    }
                }
                c.disconnect(); f.length() > 10_000
            } catch (e: Exception) { false }
            ui.post {
                try { dlg.dismiss() } catch (e: Exception) { }
                if (!ok) { Toast.makeText(a, "Não consegui baixar. Tente de novo.", Toast.LENGTH_LONG).show(); return@post }
                val u = Uri.parse("content://com.leve.analise.img/$FILE")
                try {
                    a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { Toast.makeText(a, "Não consegui abrir o instalador", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}

/** Confere atualização a cada ~6 horas com internet e avisa na notificação. */
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val v = Updater.latest()
            val p = Store.prefs(this)
            if (v > Updater.mine(this) && p.getInt("upd_notified", 0) != v) {
                Updater.notifyNew(this, v); p.edit().putInt("upd_notified", v).apply()
            }
            jobFinished(params, false)
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters) = true
}
