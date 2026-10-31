package com.tingye.app.reader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.text.format.DateFormat
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LineHeightSpan
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.OverScroller
import kotlinx.coroutines.*
import kotlin.math.*

data class ReaderPalette(val id: String, val label: String, val paper: Int, val ink: Int, val grain: Boolean = false)
val readerPalettes = listOf(
    ReaderPalette("eye", "护眼", 0xFFDCE6D6.toInt(), 0xFF29372C.toInt()),
    ReaderPalette("parchment", "羊皮纸", 0xFFE4CEA4.toInt(), 0xFF493A25.toInt(), true),
    ReaderPalette("paper", "纸质", 0xFFF5F0E7.toInt(), 0xFF38342F.toInt(), true),
    ReaderPalette("white", "素白", 0xFFFAFAF8.toInt(), 0xFF282B29.toInt()),
    ReaderPalette("night", "夜读", 0xFF1B211E.toInt(), 0xFFB9C3BA.toInt()))

/** Layout/neighbor-page rasterization run off the UI thread; gestures only move cached pages. */
class ReaderCanvas(context: Context) : View(context) {
    var onBackdropInvalidated:(()->Unit)?=null
    override fun invalidate(){onBackdropInvalidated?.invoke();super.invalidate()}
    override fun postInvalidateOnAnimation(){onBackdropInvalidated?.invoke();super.postInvalidateOnAnimation()}
    var onListenAtOffset:(Int)->Unit={}
    var onPosition: (Int, Boolean) -> Unit = { _, _ -> }
    var onVisiblePosition:(Int,Int,Boolean)->Unit={_,_,_->}
    var onBoundary: (Int) -> Unit = { }
    var onMenu: () -> Unit = { }
    var onUserTouch: () -> Unit = { }
    var onPage: (Int, Int) -> Unit = { _, _ -> }
    private data class Page(val start: Int, val end: Int, val top: Int, val bottom: Int)
    private data class Typeset(val chapter: Int,val text: String, val layout: StaticLayout, val pages: List<Page>)
    private class ParagraphGap(private val pixels: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) {
            fm.ascent=-pixels;fm.top=-pixels;fm.descent=0;fm.bottom=0;fm.leading=0
        }
    }
    private data class Input(val chapter: Int, val texts: Map<Int, String>, val style: ReaderStyle,
        val chapterCount: Int,val safeTop: Int,val safeBottom: Int)
    private data class Raster(val front: Bitmap, val back: Bitmap, val backPaint:Paint?=null)
    private data class PageKey(val chapter:Int,val index:Int)
    private data class LayoutSpec(val style:ReaderStyle,val width:Int,val height:Int,val safeTop:Int,val safeBottom:Int,val fontScale:Float)
    private var layoutSpec:LayoutSpec?=null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private var cacheWork: Job? = null
    private var input: Input? = null
    private var layouts = emptyMap<Int, Typeset>()
    private var page = 0
    private var offset = 0
    private var navigation = -1
    private var desiredOffset = 0
    private var longPressHandled=false
    private val listenLongPress=Runnable {
        val book=current
        if(book!=null && !dragging && downY>=top && downY<height-bottom && downX>=margin && downX<=width-margin){
            longPressHandled=true
            val vertical=(if(scrolling)scroll else book.pages[page].top.toFloat())+downY-top
            val line=book.layout.getLineForVertical(vertical.toInt().coerceIn(0,(book.layout.height-1).coerceAtLeast(0)))
            val character=book.layout.getOffsetForHorizontal(line,downX-margin).coerceIn(0,book.text.length)
            onUserTouch();onListenAtOffset(character)
        }
    }
    private var generation = 0
    private var cache = emptyMap<PageKey, Raster>()
    private val meshColumns=64
    private val meshRows=32
    private val vertices=FloatArray((meshColumns+1)*(meshRows+1)*2)
    private val textureCoordinates=FloatArray(vertices.size)
    private val meshIndices=ShortArray(meshColumns*meshRows*6).apply{
        var at=0
        for(row in 0 until meshRows)for(column in 0 until meshColumns){
            val a=row*(meshColumns+1)+column;val b=a+meshColumns+1
            this[at++]=a.toShort();this[at++]=b.toShort();this[at++]=(a+1).toShort()
            this[at++]=(a+1).toShort();this[at++]=b.toShort();this[at++]=(b+1).toShort()
        }
    }
    private val frontColors=IntArray((meshColumns+1)*(meshRows+1))
    private val backColors=IntArray(frontColors.size)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadow = Paint()
    private var texture: Bitmap? = null
    private var palette = readerPalettes.first()
    private var margin = 0f
    private var top = 0f
    private var bottom = 0f
    private var scroll = 0f
    private val scroller = OverScroller(context)
    private var animator: ValueAnimator? = null
    private var travel = 0f
    private var travelVelocity=0f
    private val turnTiming=ReaderTurnTiming()
    private var direction = 1
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var touching=false
    private val pendingTurns=java.util.ArrayDeque<Int>()
    private var waitingForChapter:Int?=null
    private var settlingCommit=false
    private var caughtCommit=false
    private var caughtDirection=1
    private var dragOrigin=0f
    private var boundaryGesture=false
    private val clockPaint=TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var clockMinute=-1L
    private var clockLabel=""
    private val minuteTick=object:Runnable{
        override fun run(){invalidate();scheduleClock()}
    }
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val bodyHeight get() = (height - top - bottom).coerceAtLeast(1f)
    private val current get() = input?.let { layouts[it.chapter] }
    private val scrolling get() = input?.style?.animation == "scroll"

    init { isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; contentDescription = "小说正文" }

    fun bind(chapter: Int, texts: Map<Int, String>, style: ReaderStyle, requestedOffset: Int, navigationId: Int,
        chapterCount: Int,safeTop: Int,safeBottom: Int) {
        val next = Input(chapter, texts, style,chapterCount,safeTop,safeBottom)
        val newNavigation = navigationId != navigation
        navigation = navigationId
        if (next != input) {
            if(waitingForChapter!=chapter || input?.style!=style)pendingTurns.clear()
            waitingForChapter=null
            desiredOffset = requestedOffset
            input = next
            // Neighbour layouts already exist while the next chapter's larger
            // neighbourhood loads. Never draw the previous chapter's page index.
            current?.let{book->page=book.pages.indexOfLast{it.start<=requestedOffset}.coerceAtLeast(0)}
            palette = readerPalettes.firstOrNull { it.id == style.theme } ?: readerPalettes.first()
            setBackgroundColor(palette.paper)
            prepare()
        } else if (newNavigation && layouts.isNotEmpty()) {
            pendingTurns.clear()
            seek(requestedOffset)
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        var at=0
        for(row in 0..meshRows)for(column in 0..meshColumns){
            textureCoordinates[at++]=w*column.toFloat()/meshColumns
            textureCoordinates[at++]=h*row.toFloat()/meshRows
        }
        if(current!=null)desiredOffset=offset
        prepare()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.isActive) { scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate); prepare() }
        scheduleClock()
    }
    override fun onWindowVisibilityChanged(visibility:Int){super.onWindowVisibilityChanged(visibility);if(visibility==VISIBLE)scheduleClock()else removeCallbacks(minuteTick)}
    private fun scheduleClock(){removeCallbacks(minuteTick);if(isAttachedToWindow && windowVisibility==VISIBLE)postDelayed(minuteTick,60_000L-System.currentTimeMillis()%60_000L)}
    override fun onDetachedFromWindow() {
        removeCallbacks(minuteTick);removeCallbacks(listenLongPress); animator?.cancel(); scroller.abortAnimation(); scope.cancel(); super.onDetachedFromWindow() }

    private fun prepare() {
        val config = input ?: return
        if (width <= 0 || height <= 0) return
        val w = width; val h = height
        val density = resources.displayMetrics.density
        margin = config.style.margin * density
        top = 20f * density+config.safeTop
        bottom = 46f * density+config.safeBottom
        val body = (h - top - bottom).toInt().coerceAtLeast(1)
        val textWidth = (w - margin*2).toInt().coerceAtLeast(1)
        val targetOffset = desiredOffset
        val spec=LayoutSpec(config.style,w,h,config.safeTop,config.safeBottom,resources.displayMetrics.scaledDensity)
        val reusableLayouts=if(layoutSpec==spec)layouts else emptyMap()
        val request = ++generation
        work?.cancel(); cacheWork?.cancel(); animator?.cancel();animator=null; travel = 0f; scroller.abortAnimation()
        work = scope.launch {
            val prepared = withContext(Dispatchers.Default) {
                val typeface = when (config.style.font) {
                    "serif" -> Typeface.create("serif", Typeface.NORMAL)
                    "mono" -> Typeface.create("monospace", Typeface.NORMAL)
                    "custom" -> config.style.fontPath?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() } ?: Typeface.DEFAULT
                    else -> Typeface.create("sans-serif", Typeface.NORMAL)
                }
                config.texts.mapValues { (chapter, source) ->
                    ensureActive()
                    reusableLayouts[chapter]?.takeIf{it.text==source}?.let{return@mapValues it}
                    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                        color = palette.ink; textSize = config.style.fontSize * resources.displayMetrics.scaledDensity
                        this.typeface = typeface
                    }
                    // Empty separator lines are paragraph spacing, not another
                    // full CJK line. Preserve all source offsets for audio sync.
                    val styled=SpannableString(source)
                    Regex("(?m)^[\\t \\u3000]*\\n").findAll(source).forEach { gap ->
                        styled.setSpan(ParagraphGap((textPaint.textSize*.45f).toInt().coerceAtLeast(1)),
                            gap.range.first,gap.range.last+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    val builder = StaticLayout.Builder.obtain(styled, 0, styled.length, textPaint, textWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                        .setLineSpacing(0f, config.style.spacing).setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    if (Build.VERSION.SDK_INT >= 28) builder.setUseLineSpacingFromFallbacks(true)
                    val layout = builder.build()
                    val pages = mutableListOf<Page>()
                    var first = 0
                    while (first < layout.lineCount) {
                        var last = first
                        while (last + 1 < layout.lineCount && contentBottom(layout,last+1) - layout.getLineTop(first) <= body) last++
                        pages += Page(layout.getLineStart(first), layout.getLineEnd(last).coerceAtMost(source.length),
                            layout.getLineTop(first),contentBottom(layout,last))
                        first = last + 1
                    }
                    Typeset(chapter,source, layout, pages.ifEmpty { listOf(Page(0, 0, 0, 0)) })
                }
            }
            if (request != generation) return@launch
            layouts = prepared
            layoutSpec=spec
            cache = cache.filterKeys{key->reusableLayouts[key.chapter]?.text==prepared[key.chapter]?.text && key.chapter in reusableLayouts}
            texture = makeTexture(w, h)
            seek(targetOffset)
        }
    }
    private fun contentBottom(layout: StaticLayout, line: Int): Int {
        // The last line on a page does not need spacing for a following line.
        if(Build.VERSION.SDK_INT>=34) return layout.getLineBottom(line,false)
        if(line==layout.lineCount-1) return layout.getLineBottom(line)
        val height=layout.getLineBottom(line)-layout.getLineTop(line)
        val natural=((height-layout.spacingAdd)/layout.spacingMultiplier).roundToInt()
        return layout.getLineBottom(line)-(height-natural).coerceAtLeast(0)
    }
    private fun makeTexture(w: Int, h: Int): Bitmap? {
        if (!palette.grain) return null
        val tile = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        val p = Paint().apply { color = Color.BLACK; alpha = if (palette.id == "parchment") 9 else 5; strokeWidth = 1f }
        val random = java.util.Random(20261025)
        repeat(500) { canvas.drawPoint(random.nextInt(128).toFloat(), random.nextInt(128).toFloat(), p) }
        return tile
    }
    private fun background(canvas: Canvas, w: Int = width, h: Int = height) {
        val backgroundPaint = Paint()
        backgroundPaint.color = palette.paper
        // AndroidView's canvas may include its Compose siblings. Keep the paper
        // inside this view instead of clearing the header with drawColor().
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), backgroundPaint)
        texture?.let {
            backgroundPaint.shader = BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), backgroundPaint); backgroundPaint.shader = null
        }
        if (palette.id == "parchment") {
            backgroundPaint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0x0A79562B, Color.TRANSPARENT, 0x0E79562B), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), backgroundPaint)
        }
    }
    private fun seek(target: Int) {
        val typeset = current ?: return
        animator?.cancel();animator=null; travel = 0f; scroller.abortAnimation()
        offset = target.coerceIn(0, typeset.text.length)
        if (scrolling) {
            val line = typeset.layout.getLineForOffset(offset)
            scroll = typeset.layout.getLineTop(line).toFloat().coerceIn(0f, maxScroll())
        }
        page = typeset.pages.indexOfLast { it.start <= offset }.coerceAtLeast(0)
        offset = if (scrolling) typeset.layout.getLineStart(typeset.layout.getLineForVertical(scroll.toInt())) else typeset.pages[page].start
        reportPosition(false)
        onPage(page + 1, typeset.pages.size)
        refreshCache()
        invalidate()
    }
    private fun pageAt(delta: Int): Pair<Typeset, Int>? {
        val config = input ?: return null
        val book = current ?: return null
        var entry=book;var index=page+delta
        while(index<0){entry=layouts[entry.chapter-1]?:return null;index+=entry.pages.size}
        while(index>=entry.pages.size){index-=entry.pages.size;entry=layouts[entry.chapter+1]?:return null}
        return entry to index
    }
    private fun pageKey(delta:Int)=pageAt(delta)?.let{PageKey(it.first.chapter,it.second)}
    private fun raster(delta:Int)=pageKey(delta)?.let{cache[it]}
    private fun drawPage(canvas: Canvas, book: Typeset, index: Int) {
        background(canvas)
        val entry = book.pages[index]
        canvas.save()
        // Measure and draw with the same layout, including Chinese fallback-font
        // metrics. Rebuilding the substring omitted those metrics and compressed
        // every rendered line, leaving a large blank block beneath each page.
        // Clip at the measured last line, not the viewport, to exclude the next line.
        canvas.clipRect(margin, top, width-margin,min(height-bottom,top+entry.bottom-entry.top))
        canvas.translate(margin, top-entry.top)
        book.layout.draw(canvas)
        canvas.restore()
        footer(canvas,book,index)
    }
    private fun footer(canvas: Canvas,book: Typeset,index: Int) {
        val config=input ?: return
        val label=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color=palette.ink;alpha=140;textSize=11f*resources.displayMetrics.scaledDensity
            typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        }
        val metrics=label.fontMetrics
        val y=height-config.safeBottom-12f*resources.displayMetrics.density-(metrics.ascent+metrics.descent)/2f
        canvas.drawText("${book.chapter+1}/${config.chapterCount} 章",margin,y,label)
        val pages="${index+1}/${book.pages.size} 页"
        canvas.drawText(pages,width-margin-label.measureText(pages),y,label)
    }
    private fun clock(canvas:Canvas){
        val config=input?:return
        clockPaint.apply{color=palette.ink;alpha=140;textSize=11f*resources.displayMetrics.scaledDensity;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
        val minute=System.currentTimeMillis()/60_000
        if(clockMinute!=minute){clockMinute=minute;clockLabel=DateFormat.getTimeFormat(context).format(java.util.Date())}
        val label=clockLabel
        val metrics=clockPaint.fontMetrics
        val y=config.safeTop+10f*resources.displayMetrics.density-(metrics.ascent+metrics.descent)/2f
        canvas.drawText(label,width-margin-clockPaint.measureText(label),y,clockPaint)
    }
    private fun refreshCache() {
        if (scrolling) { invalidate(); return }
        val request = generation
        val wantedPage = page
        val pages = listOf(0,direction,-direction,direction*2).mapNotNull { delta -> pageAt(delta)?.let { PageKey(it.first.chapter,it.second) to it } }
        val reusable = cache
        val needsBack = input?.style?.animation=="curl"
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        cacheWork?.cancel()
        cacheWork = scope.launch {
            cache=cache.filterKeys{key->pages.any{it.first==key}}
            for((key,entry) in pages){
            val bitmap = withContext(Dispatchers.Default) {
                ensureActive()
                reusable[key] ?: run {
                    val front=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                    drawPage(Canvas(front),entry.first,entry.second)
                    val backPaint=if(needsBack && Build.VERSION.SDK_INT>=33)Api33PaperBack.paint(front,palette.paper)else null
                    val back=if(needsBack && backPaint==null) Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also {
                        val backCanvas=Canvas(it)
                        backCanvas.drawColor(palette.paper)
                        backCanvas.drawBitmap(front,0f,0f,Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha=22 })
                    } else front
                    // Schedule texture upload before the first drag frame needs it.
                    front.prepareToDraw()
                    if(back!==front) back.prepareToDraw()
                    Raster(front,back,backPaint)
                }
            }
            if (request != generation || page != wantedPage)return@launch
            cache=cache+(key to bitmap);invalidate();drainTurns()
            }
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawReadingPage(canvas)
        clock(canvas)
    }
    private fun drawReadingPage(canvas:Canvas){
        val book = current ?: return
        if (scrolling) {
            background(canvas)
            canvas.save(); canvas.clipRect(margin, top, width-margin, height-bottom)
            canvas.translate(margin, top-scroll); book.layout.draw(canvas); canvas.restore()
            val line=book.layout.getLineForVertical(scroll.toInt())
            footer(canvas,book,book.pages.indexOfLast { it.start<=book.layout.getLineStart(line) }.coerceAtLeast(0))
            return
        }
        val currentBitmap = raster(0)
        if (currentBitmap == null) { drawPage(canvas, book, page); return }
        if (travel <= 0f || raster(direction) == null) { canvas.drawBitmap(currentBitmap.front, 0f, 0f, paint); return }
        val next = raster(direction)!!
        if (input?.style?.animation == "curl") {
            if (direction > 0) curl(canvas, currentBitmap, next, travel)
            else curl(canvas, next, currentBitmap, 1f-travel)
        } else {
            val shift = width * travel * direction
            canvas.drawBitmap(currentBitmap.front, -shift, 0f, paint)
            canvas.drawBitmap(next.front, width*direction-shift, 0f, paint)
        }
    }
    /** A continuous cylindrical paper surface. Shared mesh vertices prevent strip seams.
     * Source coordinates stay attached to the sheet; only the curved surface moves.
     * The back uses a separately cached, lightly translucent paper texture.
     */
    private fun curl(canvas: Canvas, sheet: Raster, underneath: Raster, fraction: Float) {
        val p=fraction.coerceIn(0f,1f)
        if(p<=0f) { canvas.drawBitmap(sheet.front,0f,0f,paint);return }
        if(p>=1f) { canvas.drawBitmap(underneath.front,0f,0f,paint);return }
        canvas.drawBitmap(underneath.front,0f,0f,paint)
        val wave=sin(PI.toFloat()*p)
        val angle=.12f*wave*(if(downY<height*.5f) -1f else 1f)
        val nx=cos(angle);val ny=sin(angle)
        val radius=(width*.09f*wave).coerceAtLeast(.1f)
        val reach=nx*width+abs(ny)*height
        val crease=reach*(1f-p)-PI.toFloat()*radius*p+min(0f,ny*height)
        val shadowWidth=width*.065f*wave
        if(shadowWidth>1f) {
            val edge=crease+radius
            shadow.alpha=255
            shadow.shader=LinearGradient(nx*edge,ny*edge,nx*(edge+shadowWidth),ny*(edge+shadowWidth),
                0x40000000,Color.TRANSPARENT,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),shadow)
            shadow.shader=null
        }
        var at=0
        for(row in 0..meshRows) for(column in 0..meshColumns) {
            val x=textureCoordinates[at*2]
            val y=textureCoordinates[at*2+1]
            val distance=nx*x+ny*y-crease
            val theta=(distance/radius).coerceIn(0f,PI.toFloat())
            val sine=if(distance>0f && distance<PI.toFloat()*radius)sin(theta)else 0f
            val projection=when {
                distance<=0f -> distance
                distance<PI.toFloat()*radius -> radius*sine
                else -> -(distance-PI.toFloat()*radius)
            }
            val displacement=projection-distance
            vertices[at*2]=x+nx*displacement
            vertices[at*2+1]=y+ny*displacement
            val back=distance>PI.toFloat()*radius*.5f
            val shade=if(distance<=0f) 255 else (255-46*sine).toInt().coerceIn(0,255)
            frontColors[at]=Color.argb(if(back)0 else 255,shade,shade,shade)
            val backShade=(244-28*sine).toInt().coerceIn(0,255)
            backColors[at]=Color.argb(if(back)255 else 0,backShade,backShade,backShade)
            at++
        }
        canvas.save()
        canvas.clipRect(0f,0f,width.toFloat(),height.toFloat())
        canvas.drawBitmapMesh(sheet.front,meshColumns,meshRows,vertices,0,frontColors,0,paint)
        if(sheet.backPaint!=null){
            canvas.drawVertices(Canvas.VertexMode.TRIANGLES,vertices.size,vertices,0,textureCoordinates,0,
                backColors,0,meshIndices,0,meshIndices.size,sheet.backPaint)
        }else canvas.drawBitmapMesh(sheet.back,meshColumns,meshRows,vertices,0,backColors,0,paint)
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (current == null) return true
        if(event.actionMasked==MotionEvent.ACTION_DOWN)boundaryGesture=waitingForChapter!=null
        if(boundaryGesture){
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{downX=event.x;downY=event.y}
                MotionEvent.ACTION_UP->{
                    val dx=event.x-downX
                    if(abs(dx)>slop || event.x<width*.25f || event.x>width*.75f){
                        val step=if(abs(dx)>slop){if(dx<0)1 else -1}else if(event.x<width*.25f)-1 else 1
                        if(pendingTurns.size<32)pendingTurns.add(step)
                    }
                    boundaryGesture=false;drainTurns()
                }
                MotionEvent.ACTION_CANCEL->boundaryGesture=false
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching=true
                // isRunning is false before an animator's first frame. That
                // accepted turn still belongs to the input stream.
                caughtCommit=animator!=null && settlingCommit
                caughtDirection=direction
                animator?.cancel();animator=null
                dragOrigin=travel*direction
                scroller.abortAnimation()
                downX=event.x; downY=event.y; lastY=event.y; dragging=false
                longPressHandled=false;removeCallbacks(listenLongPress)
                if(travel==0f)postDelayed(listenLongPress,ViewConfiguration.getLongPressTimeout().toLong())
                velocity?.recycle(); velocity=VelocityTracker.obtain().also { it.addMovement(event) }
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if(longPressHandled)return true
                if(hypot(event.x-downX,event.y-downY)>slop)removeCallbacks(listenLongPress)
                velocity?.addMovement(event)
                val delta = event.x-downX
                if (!dragging && (if (scrolling) abs(event.y-downY) else abs(delta)) > slop) { dragging=true; onUserTouch() }
                if (dragging) {
                    if (scrolling) { scroll=(scroll+lastY-event.y).coerceIn(0f,maxScroll()); lastY=event.y }
                    else {
                        val signed=dragOrigin-delta/width
                        direction=if(signed>=0)1 else -1
                        travel=if(pageAt(direction)!=null && raster(direction)!=null)abs(signed).coerceIn(0f,1f)else 0f
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                touching=false
                removeCallbacks(listenLongPress)
                if(longPressHandled){longPressHandled=false;dragging=false;velocity?.recycle();velocity=null;return true}
                velocity?.addMovement(event); velocity?.computeCurrentVelocity(1000)
                if (dragging) {
                    if (scrolling) {
                        val speed=-(velocity?.yVelocity ?: 0f).toInt()
                        scroller.fling(0,scroll.toInt(),0,speed,0,0,0,maxScroll().toInt())
                        postInvalidateOnAnimation(); reportScroll()
                        if (scroll <= 0 && event.y-downY > height*.2f) onBoundary(-1)
                        else if (scroll >= maxScroll() && downY-event.y > height*.2f) onBoundary(1)
                    } else {
                        turnTiming.input(event.eventTime)
                        travelVelocity=-(velocity?.xVelocity?:0f)*direction/width
                        val intent=if(event.x<downX)1 else -1
                        val commit=abs(event.x-downX)/width>.23f || (velocity?.xVelocity?:0f)*-intent>650f
                        val release=readerSwipeRelease(if(caughtCommit)caughtDirection else null,direction,intent,commit,pendingTurns.peekLast())
                        if(release.cancelPending)pendingTurns.removeLast()
                        if(release.defer!=0){
                            if(release.deferFirst)pendingTurns.addFirst(release.defer)
                            else if(pendingTurns.size<32)pendingTurns.add(release.defer)
                        }
                        settle(release.commitVisible)
                    }
                } else {
                    performClick()
                    if(event.x<width*.25f)turn(-1)else if(event.x>width*.75f)turn(1)else{if(travel>0f)settle(caughtCommit);onMenu()}
                }
                velocity?.recycle(); velocity=null; dragging=false;caughtCommit=false
                drainTurns()
            }
            MotionEvent.ACTION_CANCEL -> { touching=false;removeCallbacks(listenLongPress);longPressHandled=false;if (!scrolling) settle(false) else reportScroll(); dragging=false; velocity?.recycle(); velocity=null }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun turn(delta: Int) {
        turnTiming.input(android.os.SystemClock.uptimeMillis())
        onUserTouch()
        if (scrolling) {
            val target=(scroll+bodyHeight*.85f*delta).coerceIn(0f,maxScroll())
            if (abs(target-scroll)<1) { onBoundary(delta); return }
            scroller.startScroll(0,scroll.toInt(),0,(target-scroll).toInt(),280); postInvalidateOnAnimation()
        } else {
            val step=if(delta>=0)1 else -1
            if(pendingTurns.peekLast()==-step)pendingTurns.removeLast()else if(pendingTurns.size<32)pendingTurns.add(step)
            if((travel>0f || caughtCommit) && animator==null && waitingForChapter==null)settle(caughtCommit)
            else if(animator!=null && settlingCommit){settle(true)}
            drainTurns()
        }
    }
    private fun drainTurns(){
        if(scrolling || touching || dragging || animator!=null || travel>0f || waitingForChapter!=null)return
        val step=pendingTurns.peekFirst()?:return
        val next=pageAt(step)
        if(next==null){pendingTurns.clear();return}
        if(raster(0)==null || raster(step)==null)return
        pendingTurns.removeFirst();direction=step;settle(true)
    }
    private fun settle(commit: Boolean) {
        val end=if (commit) 1f else 0f
        animator?.cancel()
        settlingCommit=commit
        val milliseconds=turnTiming.duration(travel,end,pendingTurns.isNotEmpty(),android.os.SystemClock.uptimeMillis())
        val speed=if(travel==0f && end==1f)1.8f/(milliseconds/1000f)else travelVelocity
        val curve=ReaderTurnCurve(travel,end,speed,milliseconds)
        animator=ValueAnimator.ofFloat(0f,1f).apply {
            duration=milliseconds
            interpolator=android.view.animation.LinearInterpolator()
            addUpdateListener {
                val fraction=it.animatedValue as Float
                travel=curve.position(fraction);travelVelocity=curve.velocity(fraction);invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled=false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled=true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    animator=null
                    if (commit) {
                        val book=current ?: return
                        val next=page+direction
                        if (next in book.pages.indices) {
                            page=next; offset=book.pages[page].start
                            reportPosition(true); onPage(page+1,book.pages.size); refreshCache()
                        } else {
                            waitingForChapter=input!!.chapter+direction
                            onBoundary(direction)
                            return
                        }
                    }
                    travel=0f;travelVelocity=0f; invalidate();drainTurns()
                }
            }); start()
        }
    }
    private fun maxScroll() = ((current?.layout?.height ?: 0)-bodyHeight).coerceAtLeast(0f)
    private fun reportPosition(user:Boolean){
        val book=current ?: return
        val end=if(scrolling){
            val line=book.layout.getLineForVertical((scroll+bodyHeight-1).toInt().coerceIn(0,(book.layout.height-1).coerceAtLeast(0)))
            book.layout.getLineEnd(line)
        }else book.pages[page].end
        onPosition(offset,user)
        onVisiblePosition(offset,end,user)
    }
    private fun reportScroll() {
        val book=current ?: return
        val line=book.layout.getLineForVertical(scroll.toInt())
        offset=book.layout.getLineStart(line)
        page=book.pages.indexOfLast { it.start <= offset }.coerceAtLeast(0)
        reportPosition(true)
        onPage(page+1,book.pages.size)
    }
    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { scroll=scroller.currY.toFloat(); invalidate(); postInvalidateOnAnimation() }
        else if (scrolling && !dragging) {
            val line=current?.layout?.getLineForVertical(scroll.toInt()) ?: return
            if (current?.layout?.getLineStart(line) != offset) reportScroll()
        }
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className="android.widget.TextView"
        val book=current
        info.text=book?.let { it.text.substring(it.pages.getOrNull(page)?.start ?: 0,it.pages.getOrNull(page)?.end ?: it.text.length) }
        info.isScrollable=true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { onUserTouch();turn(1); true }
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { onUserTouch();turn(-1); true }
        else -> super.performAccessibilityAction(action,arguments)
    }
}

/** The same paper + 22/255 front print as the legacy back bitmap, evaluated
 * before vertex shading. Reuses the full-resolution front texture; no second
 * full-screen allocation, rasterization or texture upload for every page. */
@androidx.annotation.RequiresApi(33) private object Api33PaperBack {
    fun paint(front:Bitmap,paper:Int):Paint {
        val texture=BitmapShader(front,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply{setFilterMode(BitmapShader.FILTER_MODE_LINEAR)}
        val shader=RuntimeShader("""
            uniform shader front;
            uniform half4 paper;
            half4 main(float2 p) { return half4(mix(float4(paper),float4(front.eval(p)),0.08627451)); }
        """.trimIndent())
        shader.setInputShader("front",texture)
        shader.setFloatUniform("paper",Color.red(paper)/255f,Color.green(paper)/255f,Color.blue(paper)/255f,1f)
        return Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply{this.shader=shader}
    }
}
