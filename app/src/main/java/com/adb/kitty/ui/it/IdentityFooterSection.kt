package com.adb.kitty.ui.it

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.data.*
import com.adb.kitty.ui.it.help.*
import com.adb.kitty.*
import com.adb.kitty.service.*
import com.adb.kitty.R

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
import java.io.File

data class ProcessIdentity(
    val uid: Int, val euid: Int, val suid: Int, val fsuid: Int,
    val gid: Int, val egid: Int, val sgid: Int, val fsgid: Int,
    val pid: Int, val ppid: Int, val tid: Int, val pgid: Int, val sid: Int,
    val aid: Int,
    val selinuxContext: String
) {
    companion object {
        fun current(): ProcessIdentity {
            val pid = Process.myPid()
            val tid = Process.myTid()
            val ppid = runCatching { Os.getppid() }.getOrDefault(-1)
            val pgid = runCatching { Os.getpgid(0) }.getOrDefault(-1)
            val sid = runCatching { Os.getsid(0) }.getOrDefault(-1)

            // 从 /proc/self/status 中读取完整的 Real, Effective, Saved, File System IDs
            var uid = Process.myUid(); var euid = uid; var suid = uid; var fsuid = uid
            var gid = -1; var egid = -1; var sgid = -1; var fsgid = -1

            runCatching {
                File("/proc/self/status").forEachLine { line ->
                    if (line.startsWith("Uid:")) {
                        val parts = line.split("\\s+".toRegex()).drop(1).mapNotNull { it.toIntOrNull() }
                        if (parts.size >= 4) {
                            uid = parts[0]; euid = parts[1]; suid = parts[2]; fsuid = parts[3]
                        }
                    } else if (line.startsWith("Gid:")) {
                        val parts = line.split("\\s+".toRegex()).drop(1).mapNotNull { it.toIntOrNull() }
                        if (parts.size >= 4) {
                            gid = parts[0]; egid = parts[1]; sgid = parts[2]; fsgid = parts[3]
                        }
                    }
                }
            }

            // Android AID 计算：用户 App ID = UID % 100000
            val aid = uid % 100000

            // 获取 SELinux 安全上下文
            val selinuxContext = runCatching {
                File("/proc/self/attr/current").readText().trim().ifEmpty { "unknown" }
            }.getOrDefault("unsupported")

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
            .padding(start = 12.dp, end = 88.dp, top = 4.dp, bottom = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "uid: ${identity.uid} | euid: ${identity.euid} | suid: ${identity.suid} | fsuid: ${identity.fsuid}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "gid: ${identity.gid} | egid: ${identity.egid} | sgid: ${identity.sgid} | fsgid: ${identity.fsgid}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "pid: ${identity.pid} | ppid: ${identity.ppid} | tid: ${identity.tid} | pgid: ${identity.pgid} | sid: ${identity.sid} | aid: ${identity.aid}",
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
