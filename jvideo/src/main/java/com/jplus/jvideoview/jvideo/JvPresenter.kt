package com.jplus.jvideoview.jvideo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.view.*
import android.view.GestureDetector.SimpleOnGestureListener
import android.widget.LinearLayout
import com.jplus.jvideoview.data.Video
import com.jplus.jvideoview.jvideo.JvState.*
import com.jplus.jvideoview.utils.JVideoUtil
import com.jplus.jvideoview.utils.JVideoUtil.dt2progress
import tv.danmaku.ijk.media.player.AndroidMediaPlayer
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max


/**
 * @author JPlus
 * @date 2019/8/30.
 */
class JvPresenter(
    //所在的上下文
    private val mActivity: Activity,
    //播放器view
    private val mView: JvView,
    //播放器引擎,默认使用系统自带的
    private val mPlayerType: Int = PlayBackEngine.PLAYBACK_MEDIA_PLAYER,
    //播放器状态回调
    private val mCallback: JVideoCallBack
) :
    JvContract.Presenter {
    private var mSurface: Surface? = null
    private var mTextureView: TextureView? = null
    private var mParams: LinearLayout.LayoutParams? = null
    private var mAudioManager: AudioManager? = null
    private var mRunnable: Runnable? = null
    private var mSpeedRunnable: Runnable? = null
    private var mIsBackContinue: Boolean? = null

    private var mPlayState = PlayState.STATE_IDLE
    private var mPlayMode = PlayMode.MODE_NORMAL
    private var mPlayForm = PlayForm.PLAYBACK_ONE_END

    private var mStartVolume = 0
    private var mStartPosition = 0
    private var mStartLight = 0
    private var mPosition = 0
    private var mVolume = 0
    private var mLight = 0
    private var mBufferPercent = 0
    private var mVideoIndex = 0
    private var mLoadingNum = -1
    private var mAdjustWay = 0

    //是否第一次按下，用于滑动判断
    private var mIsFirstDown = true
    //是否显示中间的控制view
    private var mIsShowControllerView = false
    //是否加载中
    private var mIsLoading = false
    //是否静音
    private var mIsVolumeMute = false
    //是否强制翻转屏幕
    private var mIsForceScreen = false

    private val mHandler by lazy {
        Handler()
    }
    private val mVideoList by lazy {
        ArrayList<Video>()
    }
    private val mHideRunnable: Runnable by lazy {
        //延时后执行
        Runnable {
            mView.hideOrShowController(false)
            mIsShowControllerView = false
        }
    }
    private val mPlayer: IMediaPlayer by lazy {
        when (mPlayerType) {
            //使用ijkplayer播放引擎
            PlayBackEngine.PLAYBACK_IJK_PLAYER -> IjkMediaPlayer()
            //使用android自带的播放引擎
            PlayBackEngine.PLAYBACK_MEDIA_PLAYER -> AndroidMediaPlayer()
            else -> AndroidMediaPlayer()
        }
    }

    init {
        mView.setPresenter(this)
        //保存普通状态下的布局参数
        mParams = LinearLayout.LayoutParams((mView as View).layoutParams)
        Log.d("pipap", "orientation:" + mActivity.requestedOrientation)
    }

    override fun subscribe() {
        toLoading(true, "播放器初始化中...", 1)
        initAudio()
        toLoading(false, "播放器初始化完成", 1)
    }

    override fun unSubscribe() {

    }

    //初始化Media和volume
    private fun initAudio() {
        mAudioManager = mActivity.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            //8.0以上需要响应音频焦点的状态改变
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            /*
            AUDIOFOCUS_GAIN  的使用场景：应用需要聚焦音频的时长会根据用户的使用时长改变，属于不确定期限。例如：多媒体播放或者播客等应用。
            AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK  的使用场景：应用只需短暂的音频聚焦，来播放一些提示类语音消息，或录制一段语音。例如：闹铃，导航等应用。
            AUDIOFOCUS_GAIN_TRANSIENT  的使用场景：应用只需短暂的音频聚焦，但包含了不同响应情况，例如：电话、QQ、微信等通话应用。
            AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE  的使用场景：同样您的应用只是需要短暂的音频聚焦。未知时长，但不允许被其它应用截取音频焦点。例如：录音软件。
            */
            val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener {
                } // Need to implement listener
                .build()
            mAudioManager?.requestAudioFocus(audioFocusRequest)
        } else {
            mAudioManager?.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        //初始音量值
        mStartVolume = getVolume(false)
        //初始亮度值
        mStartLight = getLight(false)
    }

    //初始化播放器监听
    private fun initPlayerListener() {
        mPlayer.let {
            //设置是否循环播放，默认可不写
            it.isLooping = false
            //设置播放类型
            if (mPlayer is AndroidMediaPlayer) {
                Log.d(JvCommon.TAG, "AndroidMediaPlayer")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val attributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                        .build()
                    (it as AndroidMediaPlayer).internalMediaPlayer.setAudioAttributes(attributes)
                } else {
                    it.setAudioStreamType(AudioManager.STREAM_MUSIC)
                }
            } else {
                it.setAudioStreamType(AudioManager.STREAM_MUSIC)
            }

            //播放完成监听
            it.setOnCompletionListener {
                completedPlay(null)
            }

            //seekTo()调用并实际查找完成之后
            it.setOnSeekCompleteListener {
                // mPlayState = PlayState.STATE_IDLE
                Log.d(JvCommon.TAG, "setOnSeekCompleteListener")
            }

            //预加载监听
            it.setOnPreparedListener {
                preparedPlay()
            }

            //相当于缓存进度条
            it.setOnBufferingUpdateListener { _, percent ->
                buffering(percent)
            }

            //播放错误监听
            it.setOnErrorListener { _, what, extra ->
                errorPlay(what, extra, "播放错误，请重试~")
                true
            }

            //播放信息监听
            it.setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                        Log.d(JvCommon.TAG, "MEDIA_INFO_VIDEO_RENDERING_START")
                        // 播放器开始渲染
                        mPlayState = PlayState.STATE_PLAYING
                    }
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                        bufferStart()
                        Log.d(JvCommon.TAG, "MEDIA_INFO_BUFFERING_START")

                    }
                    IMediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                        buffered()
                    }
                    MediaPlayer.MEDIA_INFO_NOT_SEEKABLE -> {
                        //无法seekTo
                    }
                }
                true
            }
            //播放尺寸
            it.setOnVideoSizeChangedListener { _, _, _, _, _ ->
                //这里是视频的原始尺寸大小
                Log.d(JvCommon.TAG, "setOnVideoSizeChangedListener")
                changeVideoSize((mView as View).width, (mView as View).height)
            }
            //设置Option
            if (mPlayerType == PlayBackEngine.PLAYBACK_IJK_PLAYER) {
                (it as IjkMediaPlayer).setOption(
                    IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                    "start-on-prepared",
                    0
                )
                it.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1)
                it.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 0)
            }

            //设置是否保持屏幕常亮
            it.setScreenOnWhilePlaying(true)
            if (it is IjkMediaPlayer) {
                it.setOnNativeInvokeListener(IjkMediaPlayer.OnNativeInvokeListener { _, _ ->
                    true
                })
                it.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "reconnect", 5)
                it.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1)
            }
        }
    }

    override fun controlPlay() {
        when (mPlayState) {
            PlayState.STATE_PLAYING, PlayState.STATE_BUFFERING_PLAYING -> pausePlay()
            PlayState.STATE_PAUSED, PlayState.STATE_BUFFERING_PAUSED -> continuePlay()
            PlayState.STATE_PREPARED -> startPlay()
        }
    }

    //开始播放
    override fun startPlay(position: Int) {
        if (mPlayState == PlayState.STATE_PREPARED) {
            Log.d(JvCommon.TAG, "startPlay:$position")
            mPlayer.let {
                //如果不在播放中，指定视频播放位置并开始播放
                it.seekTo(position.toLong())
                it.start()
                mPlayState == PlayState.STATE_PLAYING
            }
            mView.startVideo(position)
            runVideoTime()
        }
    }

    override fun continuePlay() {
        Log.d(JvCommon.TAG, "continuePlay")
        when (mPlayState) {
            PlayState.STATE_PAUSED, PlayState.STATE_BUFFERING_PLAYING -> {
                mPlayer.start()
                mPlayState = PlayState.STATE_PLAYING
            }
            PlayState.STATE_BUFFERING_PAUSED -> {
                mPlayer.start()
                mPlayState = PlayState.STATE_BUFFERING_PLAYING
            }
            PlayState.STATE_ERROR -> {
                toLoading(true, "重新播放...", 2)
                entryVideoLoop()
                toLoading(false, "重新播放", 2)
            }
            PlayState.STATE_COMPLETED -> {
                entryVideoLoop()
            }
        }
        mView.continueVideo()
        runVideoTime()
    }

    private fun bufferStart() {
        Log.d(JvCommon.TAG, "bufferStart")
        // MediaPlayer暂时不播放，以缓冲更多的数据
        mPlayState =
            if (mPlayState == PlayState.STATE_PAUSED || mPlayState == PlayState.STATE_BUFFERING_PAUSED) {
                PlayState.STATE_BUFFERING_PAUSED
            } else if (mPlayState == PlayState.STATE_IDLE || mPlayState == PlayState.STATE_PREPARING) {
                return
            } else {
                PlayState.STATE_BUFFERING_PLAYING
            }
        Log.d(JvCommon.TAG, "mPlayState:${mPlayState}")
        //loading
        toLoading(true, "缓冲中....", 3)
    }

    private fun buffering(percent: Int) {
//        Log.d(JVideoCommon.TAG, "buffering$percent")
        if (percent != 0) {
            mBufferPercent = percent
        }
        mView.buffering(mBufferPercent)
        mPlayer.let {
            if (it is IjkMediaPlayer) {
                mView.showNetSpeed("" + it.tcpSpeed / 1024 + "K/s")//缓冲时显示网速
            }
        }
    }

    private fun buffered() {
        Log.d(JvCommon.TAG, "buffered")
        // 填充缓冲区后，MediaPlayer恢复播放/暂停
        if (mPlayState == PlayState.STATE_BUFFERING_PLAYING) {
            continuePlay()
        } else if (mPlayState == PlayState.STATE_BUFFERING_PAUSED) {
            pausePlay()
        }
        toLoading(false, "缓冲完成", 3)
        if (mPlayer is IjkMediaPlayer) {
            mHandler.removeCallbacks(mSpeedRunnable)
        }
    }

    override fun seekCompletePlay(position: Int) {
        Log.d(JvCommon.TAG, "seekToPlay:$position")
        mPosition = position
        mPlayer.let {
            if (mPlayState == PlayState.STATE_PAUSED || mPlayState == PlayState.STATE_BUFFERING_PAUSED) {
                it.seekTo(position.toLong())
                pausePlay()
            } else if (mPlayState == PlayState.STATE_PLAYING || mPlayState == PlayState.STATE_BUFFERING_PLAYING) {
                it.seekTo(position.toLong())
                continuePlay()
            } else if (mPlayState == PlayState.STATE_PREPARED) {
                startPlay(position)
            }
        }
    }

    override fun seekingPlay(position: Int, isSlide: Boolean) {
        mView.seekingVideo(getVideoTimeStr(position), position, isSlide)
    }

    //暂停播放
    override fun pausePlay() {
        Log.d(JvCommon.TAG, "pausePlay")
        if (mPlayState == PlayState.STATE_PLAYING || mPlayState == PlayState.STATE_PREPARED) {
            mPlayState = PlayState.STATE_PAUSED
        } else if (mPlayState == PlayState.STATE_BUFFERING_PLAYING) {
            mPlayState = PlayState.STATE_BUFFERING_PAUSED
        } else if (mPlayState == PlayState.STATE_BUFFERING_PAUSED) {
            mPlayState = PlayState.STATE_PAUSED
        }
        mPlayer.pause()
        stopVideoTime()
        showOrHideControlDelay(isShow = false, isClick = false)
        mView.pauseVideo()
    }

    override fun completedPlay(videoUrl: String?) {
        Log.d(JvCommon.TAG, "completedPlay")
//        mPlayer.let {
//            if (it.duration != it.currentPosition) {
//                errorPlay(0, 0, "网络错误或者视频地址已失效~")
//                return
//            }
//        }

        mPlayState = PlayState.STATE_COMPLETED
        when (mPlayForm) {
            PlayForm.PLAYFORM_TURN -> {
                mView.showMessagePrompt("即将播放下个视频~")
                //播放结束后，重新设置播放器，避免再次调用setDataSource()时出现IllegalStateException
                mHandler.postDelayed(
                    {
                        mVideoIndex++
                        entryVideoLoop()
                    }, 3000
                )
            }
            PlayForm.PLAYBACK_ONE_LOOP -> {
                //播放结束后的显示
                mView.showMessagePrompt("即将重新播放该视频~")
                mHandler.postDelayed(
                    {
                        entryVideoLoop()
                    }, 3000
                )
            }
            else -> //播放结束后的显示
                mView.showMessagePrompt("播放结束~")
        }
    }

    override fun preparedPlay() {
        toLoading(false, "预加载完成", 4)
        Log.d(JvCommon.TAG, "setOnPreparedListener")
        mPlayer.let {
            mPlayState = PlayState.STATE_PREPARED
            //预加载后先播放再暂停，1：防止播放错误-38(未开始就停止) 2：可以显示第一帧画面
            mView.preparedVideo(getVideoTimeStr(null), it.duration.toInt())
        }
        showOrHideControlDelay(isShow = true, isClick = false)
    }

    override fun resetPlay() {
        mPlayer.reset()
        mPlayState = PlayState.STATE_IDLE
    }

    override fun reStartPlay() {
        resetPlay()
        entryVideoLoop()
    }

    override fun errorPlay(what: Int, extra: Int, message: String) {
        Log.d(JvCommon.TAG, "setOnErrorListener:$what")
        mPlayState = PlayState.STATE_ERROR
        mView.showMessagePrompt(message)
    }

    override fun onPause() {
        //取消屏幕常亮
        mActivity.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mIsBackContinue =
            if (mPlayState == PlayState.STATE_PAUSED || mPlayState == PlayState.STATE_BUFFERING_PAUSED) {
                false
            } else if (mPlayState == PlayState.STATE_PLAYING || mPlayState == PlayState.STATE_BUFFERING_PLAYING) {
                true
            } else {
                //播放器初始化或者播放完成、播放错误时中不做任何操作
                mIsBackContinue = null
                return
            }
        pausePlay()
    }

    override fun onResume() {
        //设置屏幕常亮
        mActivity.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        //播放器初始化中不做任何操作
        mIsBackContinue?.let {
            if (!it) {
                pausePlay()
            } else {
                continuePlay()
            }
        }
    }

    //====================================================================================
    // 1.创建一个手势监听回调
    private val listener: SimpleOnGestureListener = object : SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            Log.d(JvCommon.TAG, "onDown")
            return super.onDown(e)
        }

        override fun onShowPress(e: MotionEvent) {
            Log.d(JvCommon.TAG, "onShowPress")
            super.onShowPress(e)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            Log.d(JvCommon.TAG, "onDoubleTap")
            mPlayer.let {
                if (it.isPlaying) {
                    pausePlay()
                } else if (mPlayState == PlayState.STATE_PREPARED) {
                    startPlay()
                } else if (!it.isPlaying) {
                    continuePlay()
                }
            }
            return super.onDoubleTap(e)
        }

        override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
            Log.d(JvCommon.TAG, "onSingleTapConfirmed")
            mIsShowControllerView = !mIsShowControllerView
            showOrHideControlDelay(isShow = mIsShowControllerView, isClick = true)
            return true
        }

        override fun onScroll(
            e1: MotionEvent,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (mIsFirstDown) {
                //从手指落下时判断滑动时改变的模式
                mAdjustWay = getAdjustMode(e1)
                mIsFirstDown = false
            }
            //水平滑动的距离
            val distX = e2.x - e1.x
            //竖直滑动的距离
            val distY = e2.y - e1.y
            when (mAdjustWay) {
                PlayAdjust.ADJUST_VOLUME -> {
                    //音量调节，从下往上为加，所以需要加上负号
                    if (!mIsVolumeMute) {
                        setVolume(mStartVolume, -distY)
                    }
                }
                PlayAdjust.ADJUST_LIGHT -> {
                    // 亮度调节
                    setLight(mStartLight, -distY)
                }
                PlayAdjust.ADJUST_VIDEO -> {
                    //快进/后退
                    slidePlay(mStartPosition, distX)
                }
            }
            return super.onScroll(e1, e2, distanceX, distanceY)
        }
    }
    // 2.创建一个检测器
    private val detector = GestureDetector(mActivity, listener)
    //====================================================================================

    override fun slideJudge(view: View, event: MotionEvent) {
        detector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mAdjustWay != 0) {
                    endAdjust()
                    mView.hideAdjustUi()
                }
            }
        }
    }

    private fun getAdjustMode(event: MotionEvent): Int {
        //调整前获取调整的模式
        Log.d(JvCommon.TAG, "getAdjustMode")
        val width = (mView as LinearLayout).width
        return when {
            //通过起始点坐标判断滑动是 快进/后退、亮度调节、音量调节
            event.x >= 0.8 * width -> {
                PlayAdjust.ADJUST_VOLUME
            }
            event.x <= 0.2 * width -> {
                PlayAdjust.ADJUST_LIGHT
            }
            else -> {
                mStartPosition = getPosition()
                stopVideoTime()
                showOrHideControlDelay(mIsShowControllerView, isClick = false)
                PlayAdjust.ADJUST_VIDEO
            }
        }
    }

    override fun setPlayForm(playForm: Int) {
        Log.d(JvCommon.TAG, "setPlayForm:$playForm")
        mPlayForm = playForm
    }

    override fun setVolumeMute(isMute: Boolean) {
        if (isMute) {
            //静音
            mAudioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } else {
            //恢复静音前音量
            mAudioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, mVolume, 0)
        }
        mIsVolumeMute = isMute
    }

    private fun endAdjust() {
        //调整结束后保存结果
        Log.d(JvCommon.TAG, "endAdjust")
        mIsFirstDown = true
        when (mAdjustWay) {
            PlayAdjust.ADJUST_LIGHT -> {
                //保存亮度
                mStartLight = mLight
            }
            PlayAdjust.ADJUST_VOLUME -> {
                //保存音量
                mStartVolume = mVolume
            }
            PlayAdjust.ADJUST_VIDEO -> {
                //保存并跳到指定位置播放
                mStartPosition = mPosition
                seekCompletePlay(mStartPosition)
            }
        }
        mAdjustWay = 0
    }

    override fun onConfigChanged(newConfig: Configuration) {
        if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            entryPortraitScreen()
            Log.d(JvCommon.TAG, "Configuration.ORIENTATION_PORTRAIT")
        }
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            entryFullScreen()
            Log.d(JvCommon.TAG, "Configuration.ORIENTATION_LANDSCAPE")
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun switchSpecialMode(switchMode: Int, isRotateScreen: Boolean) {
        Log.d(JvCommon.TAG, "playMode$mPlayMode")
        when (mPlayMode) {
            PlayMode.MODE_NORMAL -> {
                if (switchMode == SwitchMode.SWITCH_TO_FULL) {
                    //进入全屏模式（在dialog的模式下似乎会有适配问题）
                    mPlayMode = PlayMode.MODE_FULL_SCREEN
                    //屏幕旋转时指定带重力感应的屏幕方向不然会转不过来...，但是没有开启旋转的情况下要强制转屏来达到全屏效果
                    mActivity.requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    //屏幕方向改为未知，保证下次能够旋转屏幕
//                    mActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
            PlayMode.MODE_FULL_SCREEN -> {
                exitMode(true, isRotateScreen)
            }
        }
    }

    private fun entryFullScreen() {
        // 隐藏ActionBar、状态栏
        mActivity.actionBar?.hide()

        mActivity.window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        //设置为充满父布局
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        //隐藏虚拟按键，并且全屏
        mActivity.window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
        (mView as LinearLayout).layoutParams = params

        //全屏直接使用手机大小,此时未翻转的话，高宽对调
        val phoneWidth = JVideoUtil.getPhoneDisplayWidth(mActivity)
        val phoneHeight = JVideoUtil.getPhoneDisplayHeight(mActivity)
        changeVideoSize(
            if (phoneHeight > phoneWidth) phoneHeight else phoneWidth,
            if (phoneHeight > phoneWidth) phoneWidth else phoneHeight
        )

        mView.entryFullMode()
    }

    private fun entryPortraitScreen() {
        //进入普通模式
        mParams?.let {
            mPlayMode = PlayMode.MODE_NORMAL
            mActivity.actionBar?.show()
            mActivity.window.clearFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            mActivity.window.decorView.systemUiVisibility = View.VISIBLE
            (mView as LinearLayout).layoutParams = it
            changeVideoSize(it.width, it.height)
        }
        //屏幕方向改为未知，保证下次能够旋转屏幕
//        mActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        mView.exitMode()
    }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun exitMode(isBackNormal: Boolean, isRotateScreen: Boolean) {
        Log.d(JvCommon.TAG, "exitMode")
        if (getPlayMode() != PlayMode.MODE_NORMAL && isBackNormal) {
            mActivity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    private fun showOrHideControlDelay(isShow: Boolean, isClick: Boolean) {
        Log.d(
            JvCommon.TAG,
            "showOrHideControlDelay:show:$isShow, mIsShowControllerView:$mIsShowControllerView"
        )
        if (isShow) {
            mView.hideOrShowController(true)
            mIsShowControllerView = true
            if (mPlayState == PlayState.STATE_PLAYING || mPlayState == PlayState.STATE_BUFFERING_PLAYING) {
                mHandler.postDelayed(mHideRunnable, 5000)
            }
        } else {
            mHandler.removeCallbacks(mHideRunnable)
            if (isClick) {
                mView.hideOrShowController(false)
                mIsShowControllerView = false
            }
        }
    }

    private fun runVideoTime() {
        //开始计时
        mRunnable = mRunnable ?: Runnable {
            mPlayer.let {
                if (it.isPlaying) {
                    //更新播放进度
                    mView.playing(
                        getVideoTimeStr(it.currentPosition.toInt()),
                        it.currentPosition.toInt()
                    )
                }
            }
            //重复调起自身
            mHandler.postDelayed(mRunnable, 200)
        }
        mHandler.post(mRunnable)
        showOrHideControlDelay(mIsShowControllerView, isClick = false)
    }

    private fun stopVideoTime() {
        //停止计时
        mHandler.removeCallbacks(mRunnable)
    }

    private fun toLoading(isLoading: Boolean, content: String, loadingNum: Int) {
        mLoadingNum = if (isLoading) {
            if (mIsLoading) {
                Log.e(JvCommon.TAG, "not to close loading view:$mLoadingNum.")
            }
            mIsLoading = true
            loadingNum
        } else {
            if (!mIsLoading) {
                Log.e(JvCommon.TAG, "not to open loading view:$mLoadingNum.")
            } else if (mLoadingNum != loadingNum && mLoadingNum != -1) {
                Log.e(JvCommon.TAG, "no num for loading view:$loadingNum.")
            }
            mIsLoading = false
            -1
        }
        Log.d(JvCommon.TAG, "showLoading:$isLoading,mLoadingNum:$mLoadingNum")
        mView.showLoading(isLoading, content)
    }

    override fun textureReady(surface: SurfaceTexture, textureView: TextureView) {
        Log.d(JvCommon.TAG, "textureReady")
        if (mSurface == null) {
            mSurface = Surface(surface)
        }
        mTextureView = mTextureView ?: textureView
        mPlayState = PlayState.STATE_PREPARING
        mCallback.initSuccess()
    }

    //获取数据源
    fun start(videos: List<Video>) {
        Log.d(JvCommon.TAG, " videos:${videos.size}")
        if (videos.isEmpty()) {
            mView.showMessagePrompt("数据源为空~")
            return
        }
        mVideoList.clear()
        mVideoList.addAll(videos)
        entryVideoLoop()
    }

    override fun entryVideoLoop() {
        //播放后如果有顺序播放，则重新开始播放列表
        mSurface?.let {
            Log.d(JvCommon.TAG, "mVideoIndex:$mVideoIndex")
            mView.buffering(0) //清空前一集的缓存进度条
            entryVideo(it, mVideoList[mVideoIndex])
        }
    }

    override fun getPlayState(): Int {
        return mPlayState
    }

    override fun getPlayMode(): Int {
        return mPlayMode
    }

    override fun getLight(isMax: Boolean): Int {
        var nowBrightnessValue = 0
        try {
            nowBrightnessValue =
                Settings.System.getInt(mActivity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) {

        }
        return nowBrightnessValue
    }

    override fun getVolume(isMax: Boolean): Int {
        return if (isMax) {
            mAudioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        } else {
            mAudioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)
        } ?: 0
    }

    override fun getDuration(): Int {
        return mPlayer.duration.toInt()
    }

    override fun getPosition(): Int {
        return mPlayer.currentPosition.toInt()
    }

    override fun getBufferPercent(): Int {
        return mBufferPercent
    }

    override fun releasePlay(destroyUi: Boolean) {
        mSurface?.release()
        mHandler.removeCallbacks(mRunnable)
        mPlayer.stop()
        mPlayer.release()//调用release()方法来释放资源，资源可能包括硬件加速组件的单态固件
        mSurface = null
    }


    /**
     * 滑动屏幕快进或者后退
     * @param distance
     */
    private fun slidePlay(startProgress: Int, distance: Float) {
        var position =
            startProgress + floor(
                dt2progress(
                    distance,
                    getDuration(),
                    (mView as LinearLayout).width,
                    0.2
                )
            ).toInt()
        when {
            position in 0..getDuration() -> {

            }
            position < 0 -> position = 0
            else -> position = getDuration()
        }
        mPosition = position
        mView.seekingVideo(getVideoTimeStr(position), position, true)
    }

    private fun setLight(startLight: Int, distance: Float) {
        Log.d(JvCommon.TAG, "startLight:$startLight, distance$distance")
        var light = startLight + floor(
            dt2progress(
                distance,
                255,
                (mView as LinearLayout).height,
                1.0
            )
        ).toInt()
        when {
            light in 0..255 -> {
            }
            light <= 0 -> light = 0
            else -> light = 255
        }
        val params = mActivity.window.attributes
        params.screenBrightness = light / 255f
        mActivity.window.attributes = params
        //保存亮度
        mLight = light
        mView.setLightUi(floor(light / 255f * 100).toInt())
    }

    private fun setVolume(startVolume: Int, distance: Float) {
        var volume =
            startVolume + floor(
                dt2progress(
                    distance,
                    getVolume(true),
                    (mView as LinearLayout).height,
                    1.0
                )
            ).toInt()
        when {
            volume in 0..getVolume(true) -> {

            }
            volume <= 0 -> volume = 0
            else -> volume = getVolume(true)
        }
        mAudioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        mVolume = volume
        mView.setVolumeUi(volume * 100 / getVolume(true))
    }

    //进入视频播放准备
    private fun entryVideo(surface: Surface, video: Video) {
        toLoading(true, "视频预加载...", 4)
        Log.d(JvCommon.TAG, "entryVideo:${video.videoUrl}")
        //设置title
        mView.setTitle(video.videoName ?: "未知视频")
        mPlayer.let {

            //如果不是IDLE状态就改变播放器状态
            if (mPlayState != PlayState.STATE_IDLE) {
                resetPlay()
            }
            try {
                val url = video.videoUrl
                it.dataSource = (url)
                //加载url之后为播放器初始化完成状态
                mPlayState = PlayState.STATE_INITLIZED
                //设置渲染画板
                it.setSurface(surface)
                //初始化播放器监听
                initPlayerListener()
                //异步的方式装载流媒体文件
                it.prepareAsync()
            } catch (e: IOException) {
                e.printStackTrace()
                errorPlay(0, 0, "视频路径有误或者地址失效~")
            }
        }
    }

    //按比例改变视频大小适配屏幕宽高
    private fun changeVideoSize(mJVideoWidth: Int, mJVideoHeight: Int) {
        val jWidth = if (mJVideoWidth < 0) 1080 else mJVideoWidth
        mPlayer.let {
            val videoWidth = it.videoWidth
            val videoHeight = it.videoHeight
            //根据视频尺寸去计算->视频可以在TextureView中放大的最大倍数。
            val max =
                //竖屏模式下按视频宽度计算放大倍数值
                max(videoHeight * 1.0 / mJVideoHeight, videoWidth * 1.0 / jWidth)
            //视频宽高分别/最大倍数值 计算出放大后的视频尺寸
            val videoWidth2 = ceil(videoWidth * 1.0 / max).toInt()
            val videoHeight2 = ceil(videoHeight * 1.0 / max).toInt()
            Log.d(
                JvCommon.TAG,
                "mPlayer:$videoWidth - $videoHeight， jvideo：$jWidth- $mJVideoHeight, changed:$videoWidth2-$videoHeight2"
            )
            //无法直接设置视频尺寸，将计算出的视频尺寸设置到surfaceView 让视频自动填充。
            mTextureView?.layoutParams = LinearLayout.LayoutParams(videoWidth2, videoHeight2)
        }

    }


    private fun getVideoTimeStr(position: Int?): String {
        return JVideoUtil.progress2Time(position) + "&" + JVideoUtil.progress2Time(getDuration())
    }

    interface JVideoCallBack {
        /**
         * 播放器初始化完成
         */
        fun initSuccess()
    }

}