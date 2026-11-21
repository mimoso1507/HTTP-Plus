package com.httpplus.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** Bytes totales que han pasado por el túnel desde que arrancó el servicio. */
object Stats {
    val up = AtomicLong(0)      // subida: del equipo hacia internet
    val down = AtomicLong(0)    // bajada: de internet hacia el equipo
}

/** Un registro por conexión SSH establecida. Solo el usuario los borra. */
data class Rec(val id: Long, val host: String, val start: Long, var end: Long, var up: Long, var down: Long)

object History {
    private fun p(ctx: Context) = ctx.getSharedPreferences("hist", Context.MODE_PRIVATE)

    @Synchronized
    fun load(ctx: Context): List<Rec> {
        val out = ArrayList<Rec>()
        try {
            val a = JSONArray(p(ctx).getString("list", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Rec(o.getLong("id"), o.optString("host"), o.getLong("start"), o.optLong("end", 0), o.optLong("up", 0), o.optLong("down", 0)))
            }
        } catch (_: Exception) {}
        return out
    }

    @Synchronized
    private fun write(ctx: Context, list: List<Rec>) {
        val a = JSONArray()
        for (r in list) a.put(JSONObject().put("id", r.id).put("host", r.host).put("start", r.start).put("end", r.end).put("up", r.up).put("down", r.down))
        p(ctx).edit().putString("list", a.toString()).apply()
    }

    @Synchronized
    fun upsert(ctx: Context, r: Rec) {
        val l = load(ctx).toMutableList()
        val i = l.indexOfFirst { it.id == r.id }
        if (i >= 0) l[i] = r else l.add(r)
        write(ctx, l)
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) = write(ctx, load(ctx).filter { it.id != id })

    @Synchronized
    fun clear(ctx: Context) = write(ctx, emptyList())
}

fun fmtBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024L * 1024 -> String.format("%.1f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format("%.2f MB", b / 1048576.0)
    else -> String.format("%.2f GB", b / 1073741824.0)
}

fun fmtSpeed(bps: Long): String = when {
    bps < 1024 -> "$bps B/s"
    bps < 1024L * 1024 -> String.format("%.1f KB/s", bps / 1024.0)
    else -> String.format("%.2f MB/s", bps / 1048576.0)
}
