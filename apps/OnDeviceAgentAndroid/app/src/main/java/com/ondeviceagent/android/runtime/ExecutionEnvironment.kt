package com.ondeviceagent.android.runtime

import com.ondeviceagent.android.automation.AgentActionRequest
import com.ondeviceagent.android.automation.AgentActionResult

/** 可替换的设备边界；生产默认使用无障碍服务，回归测试不操作真实手机。 */
interface ExecutionEnvironment {
    fun ready(): Boolean
    suspend fun screenshot(halfResolution: Boolean): String?
    fun screenInfo(step: Int): String
    suspend fun perform(request: AgentActionRequest): AgentActionResult
}
