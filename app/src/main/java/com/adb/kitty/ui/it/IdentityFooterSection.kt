package com.adb.kitty.ui.it

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.data.*
import com.adb.kitty.ui.it.help.*
import com.adb.kitty.*
import com.adb.kitty.service.*
import com.adb.kitty.R

import android.annotation.SuppressLint
import android.os.Build
import android.os.Process
import android.system.Os
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File

data class ProcessIdentity(
    val uid: Int, val euid: Int, val suid: Int, val fsuid: Int,
    val gid: Int, val egid: Int, val sgid: Int, val fsgid: Int,
    val pid: Int, val ppid: Int, val tid: Int, val pgid: Int, val sid: Int,
    val aid: Int,
    val selinuxContext: String
) {
    companion object {
        @SuppressLint("NewApi")
        private fun getPgidAndSid(): Pair<Int, Int> {
            return try {
                val libcoreClass = Class.forName("libcore.io.Libcore")
                val osField = libcoreClass.getDeclaredField("os")
                osField.isAccessible = true
                val osInstance = osField.get(null) ?: return -1 to -1
                val osClass = osInstance.javaClass

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val pgid = (HiddenApiBypass.invoke(osClass, osInstance, "getpgid", 0) as? Int) ?: -1
                    val sid = (HiddenApiBypass.invoke(osClass, osInstance, "getsid", 0) as? Int) ?: -1
                    pgid to sid
                } else {
                    // API < 28 无隐藏 API 限制，使用标准反射
                    val getpgidMethod = osClass.getMethod("getpgid", Int::class.javaPrimitiveType)
                    val getsidMethod = osClass.getMethod("getsid", Int::class.javaPrimitiveType)
                    val pgid = (getpgidMethod.invoke(osInstance, 0) as? Int) ?: -1
                    val sid = (getsidMethod.invoke(osInstance, 0) as? Int) ?: -1
                    pgid to sid
                }
            } catch (_: Throwable) {
                -1 to -1
            }
        }

        fun current(): ProcessIdentity {
            val pid = Process.myPid()
            val tid = Process.myTid()
            val ppid = try { Os.getppid() } catch (_: Throwable) { -1 }

            val (pgid, sid) = getPgidAndSid()

            var uid = Process.myUid(); var euid = uid; var suid = uid; var fsuid = uid
            var gid = -1; var egid = -1; var sgid = -1; var fsgid = -1

            try {
                File("/proc/self/status").forEachLine { line ->
                    if (line.startsWith("Uid:")) {
                        val parts = line.split(Regex("\\s+")).drop(1)
                        if (parts.size >= 4) {
                            uid = parts[0].toIntOrNull() ?: uid
                            euid = parts[1].toIntOrNull() ?: euid
                            suid = parts[2].toIntOrNull() ?: suid
                            fsuid = parts[3].toIntOrNull() ?: fsuid
                        }
                    } else if (line.startsWith("Gid:")) {
                        val parts = line.split(Regex("\\s+")).drop(1)
                        if (parts.size >= 4) {
                            gid = parts[0].toIntOrNull() ?: gid
                            egid = parts[1].toIntOrNull() ?: egid
                            sgid = parts[2].toIntOrNull() ?: sgid
                            fsgid = parts[3].toIntOrNull() ?: fsgid
                        }
                    }
                }
            } catch (_: Throwable) {
            }

            val aid = uid % 100000

            val selinuxContext = try {
                val text = File("/proc/self/attr/current").readText().trim()
                if (text.isEmpty()) "unknown" else text
            } catch (_: Throwable) {
                "unsupported"
            }

            return ProcessIdentity(
                uid = uid, euid = euid, suid = suid, fsuid = fsuid,
                gid = gid, egid = egid, sgid = sgid, fsgid = fsgid,
                pid = pid, ppid = ppid, tid = tid, pgid = pgid, sid = sid,
                aid = aid,
                selinuxContext = selinuxContext
            )
        }
    }
}

@Composable
fun IdentityFooterSection(modifier: Modifier = Modifier) {
    val identity = remember { ProcessIdentity.current() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(71.dp)
            .padding(start = 10.dp, end = 98.dp, top = 4.dp, bottom = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "UID: ${identity.uid} | EUID: ${identity.euid} | SUID: ${identity.suid} | FSUID: ${identity.fsuid}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "GID: ${identity.gid} | EGID: ${identity.egid} | SGID: ${identity.sgid} | FSGID: ${identity.fsgid}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "PID: ${identity.pid} | PPID: ${identity.ppid} | TID: ${identity.tid} | PGID: ${identity.pgid} | SID: ${identity.sid} | AID: ${identity.aid}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "SELinux: ${identity.selinuxContext}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
