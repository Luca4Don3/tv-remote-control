package dev.lucasdone.tvremote.controller.session

/**
 * 在途会话登记表：统一 generation 与在途资源，保证「代数失效 + 取出在途会话」在同一把锁内完成，
 * 晚到的旧任务无法再登记；实际 `close` 在锁外执行。
 *
 * 使用约定：
 * - [nextGeneration] 开始一个新操作，返回其 token。
 * - [register] 登记该操作建立/认证中的会话；token 过期返回 false（调用方须关闭传入会话）。
 * - [clear] 认证成功并交接为当前会话后清空在途引用（仅当 token 与实例都匹配）。
 * - [invalidateAndDrain] 使所有旧 token 失效并取出在途会话（锁外关闭）。
 */
class InFlightSessionRegistry(private val closeSession: (ControllerSession) -> Unit) {
    private var generation = 0
    private var inFlight: ControllerSession? = null
    private var inFlightToken = -1

    @Synchronized
    fun nextGeneration(): Int = ++generation

    @Synchronized
    fun isCurrent(token: Int): Boolean = token == generation

    /** 读取当前代数（不递增），用于捕获操作 token。 */
    @Synchronized
    fun currentGeneration(): Int = generation

    /**
     * 同锁内递增代数并取出在途会话；关闭在锁外进行。返回新的 generation。
     */
    fun invalidateAndDrain(): Int {
        val drained: ControllerSession?
        val token: Int
        synchronized(this) {
            token = ++generation
            drained = inFlight
            inFlight = null
            inFlightToken = -1
        }
        drained?.let { runCatching { closeSession(it) } }
        return token
    }

    /**
     * 登记在途会话。token 已过期时返回 false，且不接管该会话（调用方负责关闭）。
     * 替换已有在途会话时，旧会话在锁外关闭。
     */
    fun register(token: Int, session: ControllerSession): Boolean {
        val replaced: ControllerSession?
        synchronized(this) {
            if (token != generation) return false
            replaced = inFlight?.takeIf { it !== session }
            inFlight = session
            inFlightToken = token
        }
        replaced?.let { runCatching { closeSession(it) } }
        return true
    }

    /** 仅当 token 仍当前且为同一实例时清空在途引用。 */
    fun clear(token: Int, session: ControllerSession) {
        synchronized(this) {
            if (inFlightToken == token && inFlight === session) {
                inFlight = null
                inFlightToken = -1
            }
        }
    }
}
