package ru.queuejw.lumetro.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.KeyEvent

/**
 * 朗读（TTS）的音频焦点管理，自动朗读与手动朗读共用同一实例，保证互斥。
 *
 * 之前两处朗读都是裸 [android.speech.tts.TextToSpeech.speak]，既不申请焦点也不监听
 * 焦点变化，于是：放音乐时朗读与音乐混在一起、来电/通话/语音输入时朗读照念不误。
 *
 * 这里做三件事：
 * 1. 朗读前申请 [AudioManager.AUDIOFOCUS_GAIN_TRANSIENT]——系统会让遵守焦点规则的
 *    媒体暂停（不是 MAY_DUCK 那种只压低音量）。
 * 2. 【主动管理媒体】申请焦点前若检测到后台有媒体在播，额外下发一次
 *    [KeyEvent.KEYCODE_MEDIA_PAUSE]，兜住那些不理会音频焦点的播放器；朗读正常结束
 *    归还焦点后再下发 [KeyEvent.KEYCODE_MEDIA_PLAY] 恢复——因为很多播放器收到
 *    AUDIOFOCUS_GAIN 并不会自己续播。PLAY/PAUSE 对已在对应状态的播放器是幂等的，
 *    与系统焦点恢复叠加也不会重复起播或切歌。
 * 3. 焦点被别人抢占（媒体、来电、通话、语音输入等）时立刻回调当前朗读者停止朗读；
 *    另叠加电话状态监听做双保险，部分 ROM 来电不产生焦点变化也能停。
 *    被外界打断时【不恢复媒体】——此刻打断源正在占用音频，恢复反而冲突。
 *
 * 焦点丢失后【不自动恢复朗读】——需要用户重新触发，避免通话结束突然把刚才那段念完。
 */
object TtsAudioFocus {

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    /** 当前持有焦点的朗读者（overlay / actionSheet 实例），保证同一时刻只有一个在朗读 */
    private var owner: Any? = null
    private var onLost: (() -> Unit)? = null
    private var phoneListening = false

    /** 朗读前是否有媒体在播、且被我们主动暂停过——朗读结束据此恢复 */
    private var mediaPausedByUs = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resumeMediaRunnable = Runnable {
        if (mediaPausedByUs) {
            audioManager?.let { dispatchMediaKey(it, KeyEvent.KEYCODE_MEDIA_PLAY) }
            mediaPausedByUs = false
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> notifyLost()
            // AUDIOFOCUS_GAIN 不自动恢复朗读，见类注释
        }
    }

    @Suppress("DEPRECATION")
    private val phoneListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, incomingNumber: String?) {
            if (state == TelephonyManager.CALL_STATE_RINGING ||
                state == TelephonyManager.CALL_STATE_OFFHOOK
            ) notifyLost()
        }
    }

    /**
     * 申请朗读焦点。[owner] 标识朗读者，[lost] 在被打断时回调（调用方负责停止 TTS）。
     * @return 拿到焦点才返回 true；拿不到就别朗读。
     */
    fun request(context: Context, owner: Any, lost: () -> Unit): Boolean {
        val am = audioManager ?: (context.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as? AudioManager).also { audioManager = it }
            ?: return false

        // 切换朗读者（例如自动朗读中点了一条消息的「朗读」）：先让旧的停下。
        // 注意这里不恢复媒体——旧的暂停标记要由接管的新朗读者在结束时统一恢复。
        if (this.owner != null && this.owner !== owner) notifyLost()

        // 必须在申请焦点【之前】采样：一旦焦点批下来，遵守规则的播放器已经自己停了。
        val wasPlaying = try { am.isMusicActive } catch (_: Exception) { false }

        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .setAcceptsDelayedFocusGain(false)
                .build()
            focusRequest = req
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        if (granted) {
            // 取消上一次排队但尚未执行的恢复：连续朗读之间不该冒出一次媒体起播。
            mainHandler.removeCallbacks(resumeMediaRunnable)
            if (wasPlaying) {
                // 兜底暂停不理会焦点的播放器；对已暂停的播放器则幂等无副作用。
                dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_PAUSE)
                mediaPausedByUs = true
            }
            this.owner = owner
            onLost = lost
            ensurePhoneListener(context)
        }
        return granted
    }

    /** 朗读结束/被用户停止时归还焦点，并恢复朗读前被暂停的媒体。只有当前持有者调用才生效。 */
    fun abandon(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        onLost = null
        val am = audioManager
        if (am != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusListener)
            }
        }
        focusRequest = null

        // 归还焦点后稍等一下：让系统把 GAIN 派发给播放器（遵守规则的会自行续播），
        // 我们紧接着再补一次 PLAY，兜住那些不会自行续播的播放器。两次 PLAY 幂等。
        if (mediaPausedByUs) {
            mainHandler.removeCallbacks(resumeMediaRunnable)
            mainHandler.postDelayed(resumeMediaRunnable, 400L)
        }
    }

    /** 朗读中判断焦点是否仍在自己手上（异步初始化回调里用，避免被打断后又念出来） */
    fun isHeldBy(owner: Any): Boolean = this.owner === owner

    /**
     * 焦点被抢占：清掉持有关系并通知朗读者停止；此时焦点已被系统抢走，无需再 abandon。
     * 刻意【不恢复媒体】——来电/通话/语音输入/别的媒体正占用音频，恢复会与之冲突；
     * 暂停标记留给下一次朗读的 request 统一处理，PLAY 幂等不会导致重复起播。
     */
    private fun notifyLost() {
        val callback = onLost
        owner = null
        onLost = null
        callback?.invoke()
    }

    /** 派发一次媒体播放/暂停按键给当前媒体会话 */
    private fun dispatchMediaKey(am: AudioManager, keyCode: Int) {
        try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (_: Exception) {
            // 部分定制 ROM 拒绝派发媒体按键；此时仍保留音频焦点这条主路径
        }
    }

    @Suppress("DEPRECATION")
    private fun ensurePhoneListener(context: Context) {
        if (phoneListening) return
        try {
            val tm = context.applicationContext
                .getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE)
            phoneListening = tm != null
        } catch (_: SecurityException) {
            // 没给电话权限也没关系：来电/通话一定伴随音频焦点变化，主路径已覆盖
        }
    }
}
