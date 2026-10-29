package com.ondeviceagent.android.remote

import android.content.Context
import com.ondeviceagent.android.manager.ManagerStore
import com.ondeviceagent.android.runtime.AgentRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class RemoteControl(private val context: Context, private val runtime: AgentRuntime, private val store: ManagerStore,
    private val api: FeishuApi = FeishuApi(),
    private val journalFactory: () -> RemoteJournal = { RemoteJournal(context) },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settingsStore = RemoteSettingsStore(context)
    private val status = MutableStateFlow("未启用飞书远程控制")
    val connectionState = status.asStateFlow()
    private var job: Job? = null
    @Volatile private var sharedJournal: RemoteJournal? = null
    @Volatile private var generation = 0L
    fun settings() = settingsStore.load()
    fun save(settings: RemoteSettings) { settingsStore.save(settings); refresh() }
    @Synchronized fun stop() { generation++; job?.cancel(); job = null; status.value = "远程连接已停止" }
    @Synchronized fun refresh() {
        val previous = job
        stop()
        val currentGeneration = generation
        val settings = settings()
        if (!settings.enabled) { status.value = "未启用飞书远程控制"; return }
        job = scope.launch {
            previous?.join()
            val journal = try { (sharedJournal ?: journalFactory().also { sharedJournal = it }).also { it.recoverInterrupted() } } catch (_: Exception) {
                status.value = "指令日志异常，已停止远程控制以避免重复执行"; return@launch
            }
            val processor = RemoteProcessor(context, runtime, store, journal)
            fun valid() = generation == currentGeneration && settings().enabled
            val socketJob = launch {
                var attempt = 0
                while (isActive && valid()) {
                    try {
                        val config = runtime.snapshotConfig()
                        check(config.feishuAppId.isNotBlank() && config.feishuAppSecret.isNotBlank()) { "请先保存飞书 App ID 与 App Secret" }
                        val botId = api.botId(config.feishuAppId, config.feishuAppSecret)
                        status.value = "正在连接飞书…"
                        FeishuSocket(api).run(config.feishuAppId, config.feishuAppSecret, { if (valid()) status.value = it }) { raw ->
                            if (valid() && runtime.currentConfig().feishuAppId == config.feishuAppId) {
                                val message = RemoteCommands.fromEvent(raw, settings(), config.feishuAppId, botId, System.currentTimeMillis())
                                if (message != null) journal.accept(config.feishuAppId, message, System.currentTimeMillis())
                            }
                        }
                        if (valid()) status.value = "连接已中断，准备重新连接"
                        attempt = 0
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (valid()) status.value = "连接失败；请检查凭证、机器人能力和网络，稍后自动重连" }
                    delay(minOf(60_000L, 2000L * (1L shl minOf(attempt++, 5))))
                }
            }
            // 回复网络请求独立运行，慢请求不能阻塞停止和状态查询。
            val replyJob = launch {
                while (isActive && valid()) {
                    try {
                        val config = runtime.snapshotConfig()
                        journal.replies.filter { it.appId == config.feishuAppId }.distinctBy { it.chatId }
                            .firstOrNull { it.nextAt <= System.currentTimeMillis() }?.let { reply ->
                                if (reply.userId !in settings().users() || (reply.group && reply.chatId !in settings().chats()) || System.currentTimeMillis() - reply.at > 86_400_000) journal.sent(reply.id)
                                else {
                                    try { api.send(config.feishuAppId, config.feishuAppSecret, reply); journal.sent(reply.id) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { journal.failed(reply.id, System.currentTimeMillis()); status.value = "回复暂未发出，将重试发送；不会重放任务" }
                                }
                            }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { status.value = "回复记录异常，请核对手机历史；任务不会因回复重发" }
                    delay(300)
                }
            }
            try {
                while (isActive && valid()) {
                    try {
                        processor.tick(runtime.snapshotConfig().feishuAppId, settings())
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { status.value = "指令处理异常；未自动重放，请检查手机历史" }
                    delay(300)
                }
            } finally { socketJob.cancel(); replyJob.cancel() }
        }
    }
}
