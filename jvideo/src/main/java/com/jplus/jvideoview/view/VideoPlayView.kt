package com.jplus.jvideoview.view

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import com.jplus.jvideoview.R
import kotlin.math.sqrt

/**
 * @author JPlus
 * @date 2019/11/6.
 */
class VideoPlayView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private var mPlayPaint: Paint? = null
    private var mPlayPath: Path? = null
    private var mCirclePath: Path? = null
    private var mCirclePaint: Paint? = null
    private var mWidth: Int = 0
    private var mHeight: Int = 0
    private var mIsPause = false

    private val mMinWidth by lazy {
        150
    }
    private val mMinHeight by lazy {
        mMinWidth
    }
    private var mStrokeWidth = 0f

    init {
        initPlayView(context, attrs)
    }

    private fun initPlayView(context: Context, attrs: AttributeSet) {
        val typeArray = context.obtainStyledAttributes(attrs, R.styleable.VideoPlayView)
        mStrokeWidth = typeArray.getFloat(R.styleable.VideoPlayView_stroke_width, 0f)
        initPaint(context, typeArray)
        //释放资源
        typeArray.recycle()
    }

    private fun initPaint(context: Context, typeArray:TypedArray) {
        val circleColor = typeArray.getColor(
            R.styleable.VideoPlayView_circle_color,
            ContextCompat.getColor(context, R.color.video_play_color)
        )
        val playColor = typeArray.getColor(
            R.styleable.VideoPlayView_play_color,
            ContextCompat.getColor(context, R.color.video_play_color)
        )
        setColor(playColor, circleColor)
    }

    fun setColor(playColor: Int?, circleColor:Int?) {
        playColor?.let{
            mPlayPaint = mPlayPaint?:Paint()
            mPlayPaint?.init(it)
        }

        circleColor?.let{
            mCirclePaint = mCirclePaint?:Paint()
            mCirclePaint?.init(it)
        }

    }

    private fun Paint.init(color: Int) {
        this.let {
            it.isAntiAlias = true
            it.style = Paint.Style.STROKE
            it.strokeWidth = strokeWidth
            it.strokeJoin = Paint.Join.ROUND
            it.strokeCap = Paint.Cap.ROUND
            it.color = color
            it.strokeJoin = Paint.Join.ROUND //结合处为圆角
            it.strokeCap = Paint.Cap.ROUND  // 设置转弯处为圆角
        }
    }

    private fun initDrawView(r: Float) {
        if (mStrokeWidth == 0f) {
            mStrokeWidth = r / 10
            mCirclePaint?.strokeWidth = r / 10f
            mPlayPaint?.strokeWidth = r / 10f
        }
        setDrawCircle(r, 120f, 300f)
    }


    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (mIsPause) {
            setDrawPlay(getRealWidthAndHeight(mWidth, mHeight) * 1f)
        } else {
            setDrawPause(getRealWidthAndHeight(mWidth, mHeight) * 1f)
        }
        mCirclePath?.let {
            mCirclePaint?.let{paint->
                canvas?.drawPath(it, paint)
            }
        }
        mPlayPath?.let {
            mPlayPaint?.let{paint->
                canvas?.drawPath(it, paint)
            }
        }
    }

    private fun setDrawPlay(r: Float) {
        mPlayPath = Path()
        mPlayPath?.let {
            //播放
            it.moveTo(mWidth / 2f - r / 6f, mHeight / 2f - r / 6) //移动到
            it.lineTo(mWidth / 2f - r / 6f, mHeight / 2f + r / 6)
            it.moveTo(mWidth / 2f + r / 6f, mHeight / 2f - r / 6) //移动到
            it.lineTo(mWidth / 2f + r / 6f, mHeight / 2f + r / 6)
        }
    }

    private fun setDrawPause(r: Float) {
        mPlayPath = Path()
        mPlayPath?.let {
            //暂停
            val ran = r / 4f
            val len = ran * 3 / sqrt(3f)
            it.moveTo(mWidth / 2f - ran / 2f, mHeight / 2f)
            it.quadTo(mWidth / 2f - ran / 2f, mHeight / 2f - len / 2f, mWidth / 2f + ran / 4f, mHeight / 2f - len / 4f)

            it.moveTo(mWidth / 2f - ran / 2f, mHeight / 2f)
            it.quadTo(mWidth / 2f - ran / 2f, mHeight / 2f + len / 2f, mWidth / 2f + ran / 4f, mHeight / 2f + len / 4f)

            it.moveTo(mWidth / 2f + ran / 4f, mHeight / 2f - len / 4f)
            it.quadTo(mWidth / 2f + ran, mHeight / 2f, mWidth / 2f + ran / 4f, mHeight / 2f + len / 4f)
        }
    }

    private fun setDrawCircle(r: Float, start: Float, sweep: Float) {
        mCirclePath = Path()
        val rectF1 =
            RectF(
                mWidth / 2f - r / 2f + mStrokeWidth / 2f,
                mHeight / 2f - r / 2f + mStrokeWidth / 2f,
                mWidth / 2f + r / 2f - mStrokeWidth / 2f,
                mHeight / 2f + r / 2f - mStrokeWidth / 2f
            )
        mCirclePath?.addArc(rectF1, start, sweep)
    }

    private fun getRealWidthAndHeight(widthSize: Int, heightSize: Int): Int {
        //获取各个边距的padding值
        return if (widthSize < heightSize) {
            widthSize - paddingLeft - paddingRight
        } else {
            heightSize - paddingTop - paddingBottom
        }

    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        //处理wrap_contentde情况
        if (widthMode == MeasureSpec.AT_MOST && heightMode == MeasureSpec.AT_MOST) {
            mWidth = mMinWidth
            mHeight = mMinHeight
            setMeasuredDimension(mMinWidth, mMinHeight)
        } else if (widthMode == MeasureSpec.AT_MOST) {
            mWidth = mMinWidth
            mHeight = heightSize
            setMeasuredDimension(mMinWidth, heightSize)
        } else if (heightMode == MeasureSpec.AT_MOST) {
            mWidth = widthSize
            mHeight = mMinHeight
            setMeasuredDimension(widthSize, mMinHeight)
        } else {
            mWidth = widthSize
            mHeight = heightSize
        }
        initDrawView(getRealWidthAndHeight(mWidth, mHeight) * 1f)
    }

    fun pause() {
        mIsPause = false
        invalidate()
    }

    fun play() {
        mIsPause = true
        invalidate()
    }


}