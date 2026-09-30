package com.jarves.mh.capture

import java.io.File

data class ShellResult(
    val rc: Int,
    val out: String,
    val err: String
) {
    val isSuccess: Boolean get() = rc == 0
}

object ShellRunner {

    fun exec(command: String, asRoot: Boolean = true, timeoutMs: Long = 10_000L): ShellResult {
        return try {
            val hasSu = File("/product/bin/su").exists() || File("/system/xbin/su").exists() || File("/system/bin/su").exists()
            val cmd = if (asRoot && hasSu) {
                arrayOf("su", "-c", command)
            } else {
                arrayOf("sh", "-c", command)
            }

            val process = Runtime.getRuntime().exec(cmd)
            var outText = ""
            var errText = ""

            val outThread = Thread {
                try {
                    outText = process.inputStream.bufferedReader().use { it.readText() }
                } catch (_: Exception) {}
            }
            val errThread = Thread {
                try {
                    errText = process.errorStream.bufferedReader().use { it.readText() }
                } catch (_: Exception) {}
            }

            outThread.start()
            errThread.start()

            outThread.join(timeoutMs)
            errThread.join(timeoutMs)

            val exitVal = try { process.exitValue() } catch (_: Exception) { process.destroy(); -1 }
            ShellResult(exitVal, outText.trim(), errText.trim())
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "Shell execution error")
        }
    }

    fun isRootAvailable(): Boolean {
        val res = exec("id", asRoot = true, timeoutMs = 2000L)
        return res.rc == 0 && res.out.contains("uid=0")
    }

    fun tap(x: Float, y: Float): Boolean {
        val res = exec("input tap ${x.toInt()} ${y.toInt()}", asRoot = true)
        return res.rc == 0
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int = 300): Boolean {
        val res = exec("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs", asRoot = true)
        return res.rc == 0
    }

    fun run(command: String): ShellResult {
        return exec(command, asRoot = true)
    }
}
