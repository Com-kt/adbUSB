package com.adb.kitty.ui.it

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.data.*
import com.adb.kitty.ui.it.help.*
import com.adb.kitty.*
import com.adb.kitty.service.*
import com.adb.kitty.R

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

data class ProcessIdentity(
    val uid: Int, val euid: Int, val suid: Int, val fsuid: Int,
    val gid: Int, val egid: Int, val sgid: Int, val fsgid: Int,
    val pid: Int, val ppid: Int, val tid: Int, val pgid: Int, val sid: Int,
    val aid: Int,
    val selinuxContext: String
) {
    companion object {
        fun current(): ProcessIdentity {
            val raw = NativeLibs.getRawIdentityInfo()
            val selinux = NativeLibs.getSelinuxContext() ?: "unknown"

            if (raw == null || raw.size < 14) {
                return ProcessIdentity(
                    uid = -1, euid = -1, suid = -1, fsuid = -1,
                    gid = -1, egid = -1, sgid = -1, fsgid = -1,
                    pid = -1, ppid = -1, tid = -1, pgid = -1, sid = -1,
                    aid = -1,
                    selinuxContext = selinux
                )
            }

            return ProcessIdentity(
                uid = raw[0],
                euid = raw[1],
                suid = raw[2],
                fsuid = raw[3],
                gid = raw[4],
                egid = raw[5],
                sgid = raw[6],
                fsgid = raw[7],
                pid = raw[8],
                ppid = raw[9],
                tid = raw[10],
                pgid = raw[11],
                sid = raw[12],
                aid = raw[13],
                selinuxContext = selinux
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
            .padding(start = 4.dp, end = 108.dp, top = 2.dp, bottom = 2.dp),
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
