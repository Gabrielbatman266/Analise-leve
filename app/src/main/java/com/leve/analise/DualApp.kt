package com.leve.analise

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle

/**
 * 📱 App duplo (Xiaomi "Apps duplos" / segundo perfil): abre o Threads DUPLO por padrão.
 * O app duplo roda num 2º usuário do Android; o jeito oficial de abrir app de outro perfil é o LauncherApps
 * (o mesmo que as telas iniciais usam). Desligue em ⚙ se quiser o Threads normal.
 */
object DualApp {
    private fun p(c: Context) = Store.prefs(c)
    fun on(c: Context) = p(c).getBoolean("dual_on", true)
    fun set(c: Context, v: Boolean) = p(c).edit().putBoolean("dual_on", v).apply()

    /** O perfil (usuário) onde existe a cópia dupla do app, ou null. */
    fun dualProfile(c: Context, pkg: String): UserHandle? {
        if (Build.VERSION.SDK_INT < 28) return null
        return try {
            val la = c.getSystemService(LauncherApps::class.java)
            la.profiles.firstOrNull { it != Process.myUserHandle() && la.getActivityList(pkg, it).isNotEmpty() }
        } catch (e: Exception) { ErrLog.log(c, "app duplo", e); null }
    }

    /** Abre o app (duplo quando ligado e existir). Devolve "duplo", "normal" ou "" (falhou). */
    fun launch(c: Context, pkg: String = PostQueue.THREADS): String {
        if (on(c)) try {
            val prof = dualProfile(c, pkg)
            if (prof != null) {
                val la = c.getSystemService(LauncherApps::class.java)
                val act = la.getActivityList(pkg, prof).first()
                la.startMainActivity(act.componentName, prof, null, null)
                p(c).edit().putString("dual_last", "duplo").apply()
                return "duplo"
            }
            p(c).edit().putString("dual_last", "duplo não encontrado: abri o normal").apply()
        } catch (e: Exception) { ErrLog.log(c, "app duplo", e); p(c).edit().putString("dual_last", "erro no duplo: ${e.message?.take(60)}").apply() }
        return try {
            c.startActivity(c.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (!on(c)) p(c).edit().putString("dual_last", "normal").apply()
            "normal"
        } catch (e: Exception) { "" }
    }

    fun status(c: Context): String {
        val prof = dualProfile(c, PostQueue.THREADS)
        return (if (on(c)) "ligado" else "desligado") + " · Threads duplo " + (if (prof != null) "encontrado (perfil $prof)" else "não encontrado") +
            " · última abertura: " + (p(c).getString("dual_last", "-") ?: "-")
    }
}
