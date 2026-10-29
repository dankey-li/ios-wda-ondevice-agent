package com.ondeviceagent.android.automation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Resolve launchable packages by Chinese/English display name or explicit package name.
 */
object AppLauncher {
    private val ALIASES = mapOf(
        "设置" to "com.android.settings",
        "settings" to "com.android.settings",
        "系统设置" to "com.android.settings",
        "浏览器" to "com.android.chrome",
        "chrome" to "com.android.chrome",
        "相机" to "com.android.camera",
        "电话" to "com.android.dialer",
        "短信" to "com.android.mms",
        "文件管理" to "com.android.filemanager",
        "文件" to "com.android.documentsui",
        "应用商店" to "com.heytap.market",
        "软件商店" to "com.heytap.market",
        "微信" to "com.tencent.mm",
        "wechat" to "com.tencent.mm",
        "qq" to "com.tencent.mobileqq",
        "抖音" to "com.ss.android.ugc.aweme",
        "小红书" to "com.xingin.xhs",
        "淘宝" to "com.taobao.taobao",
        "支付宝" to "com.eg.android.AlipayGphone",
        "美团" to "com.sankuai.meituan",
        "高德地图" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "哔哩哔哩" to "tv.danmaku.bili",
        "bilibili" to "tv.danmaku.bili",
        // 国泰君安「全连接」（用户常写成「全链接」）
        "全连接" to "com.gtja.office",
        "全链接" to "com.gtja.office",
        "gtja" to "com.gtja.office",
        "国泰君安" to "com.gtja.office",
    )

    /** Common spoken/written variants → canonical form for matching. */
    private val VARIANT_REPLACEMENTS = listOf(
        "链接" to "连接",
        "连接" to "链接",
        "應用" to "应用",
    )

    fun resolvePackage(context: Context, appName: String?, packageName: String?): String? {
        val explicit = packageName?.trim().orEmpty()
        if (explicit.isNotEmpty()) return explicit

        val raw = appName?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val candidates = nameCandidates(raw)
        for (name in candidates) {
            ALIASES[name.lowercase()]?.let { return it }
            ALIASES[name]?.let { return it }
            ALIASES.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.let { return it }
        }
        return findPackageByLabel(context, candidates)
    }

    fun launch(context: Context, packageName: String): Boolean {
        if (packageName == "com.android.settings") {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return true
            }
        }

        val pm = context.packageManager
        pm.getLaunchIntentForPackage(packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        }

        val launcher = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                launcher,
                PackageManager.ResolveInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcher, 0)
        }
        val activity = resolved.firstOrNull()?.activityInfo ?: return false
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(activity.packageName, activity.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun nameCandidates(raw: String): List<String> {
        val base = raw
            .replace(Regex("""(?i)[\s\-_]*(app|应用|軟體|软件)$"""), "")
            .trim()
        val out = linkedSetOf<String>()
        out += raw
        out += base
        out += base.lowercase()
        for ((from, to) in VARIANT_REPLACEMENTS) {
            if (from in base) out += base.replace(from, to)
        }
        return out.filter { it.isNotBlank() }.toList()
    }

    private fun findPackageByLabel(context: Context, needles: List<String>): String? {
        val pm = context.packageManager
        val normalizedNeedles = needles.map { normalizeForMatch(it) }.filter { it.isNotBlank() }
        if (normalizedNeedles.isEmpty()) return null

        // 1) Launcher activities (preferred — only apps with icons).
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        matchLabel(normalizedNeedles, apps.map { it.loadLabel(pm).toString() to it.activityInfo.packageName })
            ?.let { return it }

        // 2) Fallback: all installed application labels (covers odd launchers / OEM quirks).
        val flags = PackageManager.MATCH_ALL
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(flags)
        }
        return matchLabel(
            normalizedNeedles,
            installed.map { info ->
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault("")
                label to info.packageName
            },
        )
    }

    private fun matchLabel(
        needles: List<String>,
        labeled: List<Pair<String, String>>,
    ): String? {
        val entries = labeled.map { (label, pkg) -> normalizeForMatch(label) to pkg }
            .filter { it.first.isNotBlank() }

        // Exact
        for (n in needles) {
            entries.firstOrNull { it.first == n }?.let { return it.second }
        }
        // Contains either way (全链接 ↔ 全连接 after variant expansion already tried;
        // also 全链 ⊂ 全连接)
        for (n in needles) {
            entries.firstOrNull { it.first.contains(n) || n.contains(it.first) }?.let { return it.second }
        }
        // Shared prefix ≥ 2 chars (全链…)
        for (n in needles) {
            if (n.length < 2) continue
            entries.firstOrNull { it.first.take(2) == n.take(2) && (
                it.first.startsWith(n.take(minOf(3, n.length))) ||
                    n.startsWith(it.first.take(minOf(3, it.first.length)))
                )
            }?.let { return it.second }
        }
        return null
    }

    private fun normalizeForMatch(s: String): String {
        return s.trim()
            .lowercase()
            .replace(Regex("""(?i)[\s\-_]*(app|应用|軟體|软件)$"""), "")
            .replace(" ", "")
    }
}
