package com.jhomlala.better_player

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Observer
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.MediaItem.AdsConfiguration
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.drm.*
import com.google.android.exoplayer2.ext.ima.ImaAdsLoader
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.source.*
import com.google.android.exoplayer2.source.ads.AdPlaybackState
import com.google.android.exoplayer2.source.ads.AdsLoader
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.TrackSelectionOverrides
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.ui.PlayerNotificationManager.BitmapCallback
import com.google.android.exoplayer2.ui.PlayerNotificationManager.MediaDescriptionAdapter
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.util.Util
import com.jhomlala.better_player.DataSourceUtils.getDataSourceFactory
import com.jhomlala.better_player.DataSourceUtils.getUserAgent
import com.jhomlala.better_player.DataSourceUtils.isHTTP
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import io.flutter.view.TextureRegistry.SurfaceTextureEntry
import java.io.File
import java.util.*
import kotlin.math.max
import kotlin.math.min


internal class BetterPlayer(
    context: Context,
    private val eventChannel: EventChannel,
    private val textureEntry: SurfaceTextureEntry,
    customDefaultLoadControl: CustomDefaultLoadControl?,
    result: MethodChannel.Result): PlatformView {
    //////////////////S///////////////////
    private var playerView: StyledPlayerView? = null
    private var adsLoader: ImaAdsLoader? = null
    private var eventListener : AdsLoader.EventListener? = null
    private var isPlayingAd = false
    //////////////////E//////////////////
    private var exoPlayer: ExoPlayer?
    private val eventSink = QueuingEventSink()
    private val trackSelector: DefaultTrackSelector = DefaultTrackSelector(context)
    private val loadControl: LoadControl
    private var isInitialized = false
    private var surface: Surface? = null
    private var key: String? = null
    private var playerNotificationManager: PlayerNotificationManager? = null
    private var refreshHandler: Handler? = null
    private var refreshRunnable: Runnable? = null
    private var exoPlayerEventListener: Player.Listener? = null
    private var bitmap: Bitmap? = null
    private var mediaSession: MediaSessionCompat? = null
    private var drmSessionManager: DrmSessionManager? = null
    private val workManager: WorkManager
    private val workerObserverMap: HashMap<UUID, Observer<WorkInfo?>>
    private val customDefaultLoadControl: CustomDefaultLoadControl =
        customDefaultLoadControl ?: CustomDefaultLoadControl()
    private var lastSendBufferedPosition = 0L
    private val handler = Handler(Looper.myLooper()!!)
    private val inflator: LayoutInflater



    init {
        inflator = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflator.inflate(R.layout.activity_video_ad, null, false)
        ////////////////////////S////////////////////////////

        if(view.parent != null){
            (view.parent as ViewGroup).removeView(view)
        }
//        playerView = StyledPlayerView(context)
        playerView = view.findViewById(R.id.player_view)
        if(playerView?.parent != null){
            (playerView?.parent as ViewGroup).removeView(playerView)
        }
        // Create an AdsLoader.
        adsLoader = ImaAdsLoader.Builder( /* context= */context).build()
        ////////////////////////E///////////////////////////

        val loadBuilder = DefaultLoadControl.Builder()
        loadBuilder.setBufferDurationsMs(
            this.customDefaultLoadControl.minBufferMs,
            this.customDefaultLoadControl.maxBufferMs,
            this.customDefaultLoadControl.bufferForPlaybackMs,
            this.customDefaultLoadControl.bufferForPlaybackAfterRebufferMs
        )
        loadControl = loadBuilder.build()


        ////////////////////////S///////////////////////////
        // Set up the factory for media sources, passing the ads loader and ad view providers.
//        val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context)
//
//        val mediaSourceFactory: MediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
//            .setAdsLoaderProvider { unusedAdTagUri: AdsConfiguration? -> adsLoader }
//            .setAdViewProvider(playerView)

        val dataSourceFactory: DataSource.Factory =
            DefaultDataSourceFactory(context, Util.getUserAgent(playerView?.context!!, "flios"))
        val mediaSourceFactory: MediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setAdsLoaderProvider { unusedAdTagUri: AdsConfiguration? -> adsLoader }
            .setAdViewProvider(playerView)
        ////////////////////////E///////////////////////////

        exoPlayer = ExoPlayer.Builder(context)
//            .setTrackSelector(trackSelector)
//            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)///////S/////////////////E///////////////
            .build()
        ////////////////////S////////////////////////
        playerView?.player = exoPlayer
        adsLoader?.setPlayer(exoPlayer)
//        playerView.isControllerVisible
        playerView?.useController = false
//        playerView.player.
        ////////////////////E////////////////////////
        workManager = WorkManager.getInstance(context)
        workerObserverMap = HashMap()
        setupVideoPlayer(eventChannel, textureEntry, result)

        handler.postDelayed( object: Runnable {
            override fun run() {
                if(exoPlayer?.isPlayingAd != null && isPlayingAd != exoPlayer?.isPlayingAd){
                    Log.d("isPlayingAd======",exoPlayer?.isPlayingAd.toString())
                    val event: MutableMap<String, Any> = HashMap()
                    event["event"] = "isPlayingAd"
                    event["isPlayingAd"] = exoPlayer?.isPlayingAd.toString()
                    event["duration"] = getDuration()
                    eventSink.success(event)
                    isPlayingAd = exoPlayer?.isPlayingAd!!
                }
                handler.postDelayed(this, 1000)                }
        }, 0)
    }

    override fun getView(): View? {
        return playerView
    }


    fun setDataSource(
        context: Context,
        key: String?,
        dataSource: String?,
        adsSource: String?,
        formatHint: String?,
        result: MethodChannel.Result,
        headers: Map<String, String>?,
        useCache: Boolean,
        maxCacheSize: Long,
        maxCacheFileSize: Long,
        overriddenDuration: Long,
        licenseUrl: String?,
        drmHeaders: Map<String, String>?,
        cacheKey: String?,
        clearKey: String?
    ) {
        try{
            this.key = key
            isInitialized = false
            val uri = Uri.parse(dataSource)
            val adsUri = Uri.parse(adsSource)
            var dataSourceFactory: DataSource.Factory?
            val userAgent = getUserAgent(headers)
            if (licenseUrl != null && licenseUrl.isNotEmpty()) {
//                if(adsUri.path == null || adsUri.path == ""){
//                    val event: MutableMap<String, Any> = HashMap()
//                    event["event"] = "isPlayingAd"
//                    event["isPlayingAd"] = exoPlayer?.isPlayingAd.toString()
//                    event["duration"] = getDuration()
//                    eventSink.success(event)
//                }
                val httpMediaDrmCallback =
                    HttpMediaDrmCallback(licenseUrl, DefaultHttpDataSource.Factory())
                if (drmHeaders != null) {
                    for ((drmKey, drmValue) in drmHeaders) {
                        httpMediaDrmCallback.setKeyRequestProperty(drmKey, drmValue)
                    }
                }
                if (Util.SDK_INT < 18) {
                    Log.e(TAG, "Protected content not supported on API levels below 18")
                    drmSessionManager = null
                } else {
                    val drmSchemeUuid = Util.getDrmUuid("widevine")
                    if (drmSchemeUuid != null) {
                        drmSessionManager = DefaultDrmSessionManager.Builder()
                            .setUuidAndExoMediaDrmProvider(
                                drmSchemeUuid
                            ) { uuid: UUID? ->
                                try {
                                    val mediaDrm = FrameworkMediaDrm.newInstance(uuid!!)
                                    // Force L3.
                                    mediaDrm.setPropertyString("securityLevel", "L3")
                                    return@setUuidAndExoMediaDrmProvider mediaDrm
                                } catch (e: UnsupportedDrmException) {
                                    return@setUuidAndExoMediaDrmProvider DummyExoMediaDrm()
                                }
                            }
                            .setMultiSession(false)
                            .build(httpMediaDrmCallback)
                    }
                }
            } else if (clearKey != null && clearKey.isNotEmpty()) {
                drmSessionManager = if (Util.SDK_INT < 18) {
                    Log.e(TAG, "Protected content not supported on API levels below 18")
                    null
                } else {
                    DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(
                            C.CLEARKEY_UUID,
                            FrameworkMediaDrm.DEFAULT_PROVIDER
                        ).build(LocalMediaDrmCallback(clearKey.toByteArray()))
                }
            } else {
                drmSessionManager = null
            }
            if (isHTTP(uri)) {
                dataSourceFactory = getDataSourceFactory(userAgent, headers)
                if (useCache && maxCacheSize > 0 && maxCacheFileSize > 0) {
                    dataSourceFactory = CacheDataSourceFactory(
                        context,
                        maxCacheSize,
                        maxCacheFileSize,
                        dataSourceFactory
                    )
                }
            } else {
                dataSourceFactory = DefaultDataSource.Factory(context)
            }
//        val mediaSource = buildMediaSource(uri, dataSourceFactory, formatHint, cacheKey, context)
//        if (overriddenDuration != 0L) {
//            val clippingMediaSource = ClippingMediaSource(mediaSource, 0, overriddenDuration * 1000)
//            exoPlayer?.setMediaSource(clippingMediaSource)
//        } else {
//            exoPlayer?.setMediaSource(mediaSource)
//        }


            val adTagUri =
//                Uri.parse(adsUri)
                Uri.parse("https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/single_preroll_skippable&sz=640x480&ciu_szs=300x250%2C728x90&gdfp_req=1&output=vast&unviewed_position_start=1&env=vp&impl=s&correlator=")

            var adPlaybackState = AdPlaybackState(0, 500 * C.MICROS_PER_SECOND)
            adPlaybackState = adPlaybackState.withAdUri(0, 0, adTagUri)

            eventListener?.onAdPlaybackState(adPlaybackState);


            val mediaItem = MediaItem.Builder().setUri(uri)
                .setAdsConfiguration(
                    AdsConfiguration.Builder(adsUri).build()
                )  ////////////////S/////////////////////////E////////////
                .build()

            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            result.success(null)
        }catch (e:Exception){
            Log.e("Error creting view", e.message.toString())
            result.error("","","")
        }
    }

    fun setupPlayerNotification(
        context: Context, title: String, author: String?,
        imageUrl: String?, notificationChannelName: String?,
        activityName: String
    ) {
        val mediaDescriptionAdapter: MediaDescriptionAdapter = object : MediaDescriptionAdapter {
            override fun getCurrentContentTitle(player: Player): String {
                return title
            }

            @SuppressLint("UnspecifiedImmutableFlag")
            override fun createCurrentContentIntent(player: Player): PendingIntent? {
                val packageName = context.applicationContext.packageName
                val notificationIntent = Intent()
                notificationIntent.setClassName(
                    packageName,
                    "$packageName.$activityName"
                )
                notificationIntent.flags = (Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                return PendingIntent.getActivity(
                    context, 0,
                    notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            }

            override fun getCurrentContentText(player: Player): String? {
                return author
            }

            override fun getCurrentLargeIcon(
                player: Player,
                callback: BitmapCallback
            ): Bitmap? {
                if (imageUrl == null) {
                    return null
                }
                if (bitmap != null) {
                    return bitmap
                }
                val imageWorkRequest = OneTimeWorkRequest.Builder(ImageWorker::class.java)
                    .addTag(imageUrl)
                    .setInputData(
                        Data.Builder()
                            .putString(BetterPlayerPlugin.URL_PARAMETER, imageUrl)
                            .build()
                    )
                    .build()
                workManager.enqueue(imageWorkRequest)
                val workInfoObserver = Observer { workInfo: WorkInfo? ->
                    try {
                        if (workInfo != null) {
                            val state = workInfo.state
                            if (state == WorkInfo.State.SUCCEEDED) {
                                val outputData = workInfo.outputData
                                val filePath =
                                    outputData.getString(BetterPlayerPlugin.FILE_PATH_PARAMETER)
                                //Bitmap here is already processed and it's very small, so it won't
                                //break anything.
                                bitmap = BitmapFactory.decodeFile(filePath)
                                bitmap?.let { bitmap ->
                                    callback.onBitmap(bitmap)
                                }
                            }
                            if (state == WorkInfo.State.SUCCEEDED || state == WorkInfo.State.CANCELLED || state == WorkInfo.State.FAILED) {
                                val uuid = imageWorkRequest.id
                                val observer = workerObserverMap.remove(uuid)
                                if (observer != null) {
                                    workManager.getWorkInfoByIdLiveData(uuid)
                                        .removeObserver(observer)
                                }
                            }
                        }
                    } catch (exception: Exception) {
                        Log.e(TAG, "Image select error: $exception")
                    }
                }
                val workerUuid = imageWorkRequest.id
                workManager.getWorkInfoByIdLiveData(workerUuid)
                    .observeForever(workInfoObserver)
                workerObserverMap[workerUuid] = workInfoObserver
                return null
            }
        }
        var playerNotificationChannelName = notificationChannelName
        if (notificationChannelName == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val importance = NotificationManager.IMPORTANCE_LOW
                val channel = NotificationChannel(
                    DEFAULT_NOTIFICATION_CHANNEL,
                    DEFAULT_NOTIFICATION_CHANNEL, importance
                )
                channel.description = DEFAULT_NOTIFICATION_CHANNEL
                val notificationManager = context.getSystemService(
                    NotificationManager::class.java
                )
                notificationManager.createNotificationChannel(channel)
                playerNotificationChannelName = DEFAULT_NOTIFICATION_CHANNEL
            }
        }

        playerNotificationManager = PlayerNotificationManager.Builder(
            context, NOTIFICATION_ID,
            playerNotificationChannelName!!
        ).setMediaDescriptionAdapter(mediaDescriptionAdapter).build()

        playerNotificationManager?.apply {

            exoPlayer?.let {
                setPlayer(ForwardingPlayer(exoPlayer!!))
                setUseNextAction(false)
                setUsePreviousAction(false)
                setUseStopAction(false)
            }

            setupMediaSession(context)?.let {
                setMediaSessionToken(it.sessionToken)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            refreshHandler = Handler(Looper.getMainLooper())
            refreshRunnable = Runnable {
                val playbackState: PlaybackStateCompat = if (exoPlayer?.isPlaying == true) {
                    PlaybackStateCompat.Builder()
                        .setActions(PlaybackStateCompat.ACTION_SEEK_TO)
                        .setState(PlaybackStateCompat.STATE_PLAYING, position, 1.0f)
                        .build()
                } else {
                    PlaybackStateCompat.Builder()
                        .setActions(PlaybackStateCompat.ACTION_SEEK_TO)
                        .setState(PlaybackStateCompat.STATE_PAUSED, position, 1.0f)
                        .build()
                }
                mediaSession?.setPlaybackState(playbackState)
                refreshHandler?.postDelayed(refreshRunnable!!, 1000)
            }
            refreshHandler?.postDelayed(refreshRunnable!!, 0)
        }
        exoPlayerEventListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                mediaSession?.setMetadata(
                    MediaMetadataCompat.Builder()
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, getDuration())
                        .build()
                )
            }
        }
        exoPlayerEventListener?.let { exoPlayerEventListener ->
            exoPlayer?.addListener(exoPlayerEventListener)
        }
        exoPlayer?.seekTo(0)
    }

    fun disposeRemoteNotifications() {
        exoPlayerEventListener?.let { exoPlayerEventListener ->
            exoPlayer?.removeListener(exoPlayerEventListener)
        }
        if (refreshHandler != null) {
            refreshHandler?.removeCallbacksAndMessages(null)
            refreshHandler = null
            refreshRunnable = null
        }
        if (playerNotificationManager != null) {
            playerNotificationManager?.setPlayer(null)
        }
        bitmap = null
    }

    /*private fun buildMediaSource(
        uri: Uri,
        mediaDataSourceFactory: DataSource.Factory,
        formatHint: String?,
        cacheKey: String?,
        context: Context
    ): MediaSource {
        *//*val type: Int
        if (formatHint == null) {
            var lastPathSegment = uri.lastPathSegment
            if (lastPathSegment == null) {
                lastPathSegment = ""
            }
            type = Util.inferContentType(lastPathSegment)
        } else {
            type = when (formatHint) {
                FORMAT_SS -> C.TYPE_SS
                FORMAT_DASH -> C.TYPE_DASH
                FORMAT_HLS -> C.TYPE_HLS
                FORMAT_OTHER -> C.TYPE_OTHER
                else -> -1
            }
        }

        //////////////////////S/////////////////////////
//        val adTagUri = Uri.parse(context.getString(R.string.ad_tag_url))//
        val adTagUri = Uri.parse("https://storage.googleapis.com/gvabox/media/samples/stock.mp4")
        // ///////////////////E////////////////////////
        val mediaItemBuilder = MediaItem.Builder()
        mediaItemBuilder
            .setUri(uri)
        if (cacheKey != null && cacheKey.isNotEmpty()) {
            mediaItemBuilder.setCustomCacheKey(cacheKey)
        }
        val mediaItem = mediaItemBuilder
            .setAdsConfiguration(AdsConfiguration.Builder(adTagUri).build())  ////////////////S/////////////////////////E////////////
            .build()
        var drmSessionManagerProvider: DrmSessionManagerProvider? = null
        drmSessionManager?.let { drmSessionManager ->
            drmSessionManagerProvider = DrmSessionManagerProvider { drmSessionManager }
        }
        return when (type) {
            C.TYPE_SS -> SsMediaSource.Factory(
                DefaultSsChunkSource.Factory(mediaDataSourceFactory),
                DefaultDataSource.Factory(context, mediaDataSourceFactory)
            )
                .setDrmSessionManagerProvider(drmSessionManagerProvider)
                .createMediaSource(mediaItem)
            C.TYPE_DASH -> DashMediaSource.Factory(
                DefaultDashChunkSource.Factory(mediaDataSourceFactory),
                DefaultDataSource.Factory(context, mediaDataSourceFactory)
            )
                .setDrmSessionManagerProvider(drmSessionManagerProvider)
                .createMediaSource(mediaItem)
            C.TYPE_HLS -> HlsMediaSource.Factory(mediaDataSourceFactory)
                .setDrmSessionManagerProvider(drmSessionManagerProvider)
                .createMediaSource(mediaItem)
            C.TYPE_OTHER -> ProgressiveMediaSource.Factory(
                mediaDataSourceFactory,
                DefaultExtractorsFactory()
            )
                .setDrmSessionManagerProvider(drmSessionManagerProvider)
                .createMediaSource(mediaItem)
            else -> {
                throw IllegalStateException("Unsupported type: $type")
            }
        }*//*
    }*/

    private fun setupVideoPlayer(
        eventChannel: EventChannel, textureEntry: SurfaceTextureEntry, result: MethodChannel.Result
    ) {
        try {
            eventChannel.setStreamHandler(
                object : EventChannel.StreamHandler {
                    override fun onListen(o: Any?, sink: EventSink) {
                        eventSink.setDelegate(sink)
                    }

                    override fun onCancel(o: Any?) {
                        eventSink.setDelegate(null)
                    }
                })

            setAudioAttributes(exoPlayer, true)
            exoPlayer?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    Log.d("Duration==========", getDuration().toString());
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            sendBufferingUpdate(true)
                            val event: MutableMap<String, Any> = HashMap()
                            event["event"] = "bufferingStart"
                            eventSink.success(event)
                        }
                        Player.STATE_READY -> {
                            if (!isInitialized) {
                                isInitialized = true
                                sendInitialized()
                            }
                            val event: MutableMap<String, Any> = HashMap()
                            event["event"] = "bufferingEnd"
                            event["duration"] = getDuration()
                            eventSink.success(event)
                        }
                        Player.STATE_ENDED -> {
                            val event: MutableMap<String, Any?> = HashMap()
                            event["event"] = "completed"
                            event["key"] = key
                            eventSink.success(event)
                        }
                        Player.STATE_IDLE -> {
                            //no-op
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    eventSink.error("VideoError", "Video player had error $error", "")
                }
            })

            val reply: MutableMap<String, Any> = HashMap()
            reply["textureId"] = textureEntry.id()
            result.success(reply)
        }catch (e:Exception){
            Log.e("Error creting player", e.message.toString())
            result.error("","","")
        }
    }

    fun sendBufferingUpdate(isFromBufferingStart: Boolean) {
        val bufferedPosition = exoPlayer?.bufferedPosition ?: 0L
        if (isFromBufferingStart || bufferedPosition != lastSendBufferedPosition) {
            val event: MutableMap<String, Any> = HashMap()
            event["event"] = "bufferingUpdate"
            val range: List<Number?> = listOf(0, bufferedPosition)
            // iOS supports a list of buffered ranges, so here is a list with a single range.
            event["values"] = listOf(range)
            eventSink.success(event)
            lastSendBufferedPosition = bufferedPosition
        }
    }

    @Suppress("DEPRECATION")
    private fun setAudioAttributes(exoPlayer: ExoPlayer?, mixWithOthers: Boolean) {
        val audioComponent = exoPlayer?.audioComponent ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            audioComponent.setAudioAttributes(
                AudioAttributes.Builder().setContentType(C.CONTENT_TYPE_MOVIE).build(),
                !mixWithOthers
            )
        } else {
            audioComponent.setAudioAttributes(
                AudioAttributes.Builder().setContentType(C.CONTENT_TYPE_MUSIC).build(),
                !mixWithOthers
            )
        }
    }

    fun play() {
        exoPlayer?.playWhenReady = true
    }

    fun pause() {
        exoPlayer?.playWhenReady = false
    }

    fun setLooping(value: Boolean) {
        exoPlayer?.repeatMode = if (value) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    fun setVolume(value: Double) {
        val bracketedValue = max(0.0, min(1.0, value))
            .toFloat()
        exoPlayer?.volume = bracketedValue
    }

    fun setSpeed(value: Double) {
        val bracketedValue = value.toFloat()
        val playbackParameters = PlaybackParameters(bracketedValue)
        exoPlayer?.playbackParameters = playbackParameters
    }

    fun setTrackParameters(width: Int, height: Int, bitrate: Int) {
        val parametersBuilder = trackSelector.buildUponParameters()
        if (width != 0 && height != 0) {
            parametersBuilder.setMaxVideoSize(width, height)
        }
        if (bitrate != 0) {
            parametersBuilder.setMaxVideoBitrate(bitrate)
        }
        if (width == 0 && height == 0 && bitrate == 0) {
            parametersBuilder.clearVideoSizeConstraints()
            parametersBuilder.setMaxVideoBitrate(Int.MAX_VALUE)
        }
        trackSelector.setParameters(parametersBuilder)
    }

    fun seekTo(location: Int) {
        exoPlayer?.seekTo(location.toLong())
    }

    val position: Long
        get() = exoPlayer?.currentPosition ?: 0L

    val absolutePosition: Long
        get() {
            val timeline = exoPlayer?.currentTimeline
            timeline?.let {
                if (!timeline.isEmpty) {
                    val windowStartTimeMs =
                        timeline.getWindow(0, Timeline.Window()).windowStartTimeMs
                    val pos = exoPlayer?.currentPosition ?: 0L
                    return windowStartTimeMs + pos
                }
            }
            return exoPlayer?.currentPosition ?: 0L
        }

    private fun sendInitialized() {
        if (isInitialized) {
            val event: MutableMap<String, Any?> = HashMap()
            event["event"] = "initialized"
            event["key"] = key
            event["duration"] = getDuration()
            if (exoPlayer?.videoFormat != null) {
                val videoFormat = exoPlayer?.videoFormat
                var width = videoFormat?.width
                var height = videoFormat?.height
                val rotationDegrees = videoFormat?.rotationDegrees
                // Switch the width/height if video was taken in portrait mode
                if (rotationDegrees == 90 || rotationDegrees == 270) {
                    width = exoPlayer?.videoFormat?.height
                    height = exoPlayer?.videoFormat?.width
                }
                event["width"] = width
                event["height"] = height
            }
            eventSink.success(event)
        }
    }

    private fun getDuration(): Long = exoPlayer?.contentDuration ?: 0L

    /**
     * Create media session which will be used in notifications, pip mode.
     *
     * @param context                - android context
     * @return - configured MediaSession instance
     */
    @SuppressLint("InlinedApi")
    fun setupMediaSession(context: Context?): MediaSessionCompat? {
        mediaSession?.release()
        context?.let {

            val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0, mediaButtonIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
            val mediaSession = MediaSessionCompat(context, TAG, null, pendingIntent)
            mediaSession.setCallback(object : MediaSessionCompat.Callback() {
                override fun onSeekTo(pos: Long) {
                    sendSeekToEvent(pos)
                    super.onSeekTo(pos)
                }
            })
            mediaSession.isActive = true
            val mediaSessionConnector = MediaSessionConnector(mediaSession)
            mediaSessionConnector.setPlayer(exoPlayer)
            this.mediaSession = mediaSession
            return mediaSession
        }
        return null

    }

    fun onPictureInPictureStatusChanged(inPip: Boolean) {
        val event: MutableMap<String, Any> = HashMap()
        event["event"] = if (inPip) "pipStart" else "pipStop"
        eventSink.success(event)
    }

    fun disposeMediaSession() {
        if (mediaSession != null) {
            mediaSession?.release()
        }
        mediaSession = null
    }

    fun setAudioTrack(name: String, index: Int) {
        try {
            val mappedTrackInfo = trackSelector.currentMappedTrackInfo
            if (mappedTrackInfo != null) {
                for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                    if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_AUDIO) {
                        continue
                    }
                    val trackGroupArray = mappedTrackInfo.getTrackGroups(rendererIndex)
                    var hasElementWithoutLabel = false
                    var hasStrangeAudioTrack = false
                    for (groupIndex in 0 until trackGroupArray.length) {
                        val group = trackGroupArray[groupIndex]
                        for (groupElementIndex in 0 until group.length) {
                            val format = group.getFormat(groupElementIndex)
                            if (format.label == null) {
                                hasElementWithoutLabel = true
                            }
                            if (format.id != null && format.id == "1/15") {
                                hasStrangeAudioTrack = true
                            }
                        }
                    }
                    for (groupIndex in 0 until trackGroupArray.length) {
                        val group = trackGroupArray[groupIndex]
                        for (groupElementIndex in 0 until group.length) {
                            val label = group.getFormat(groupElementIndex).label
                            if (name == label && index == groupIndex) {
                                setAudioTrack(rendererIndex, groupIndex, groupElementIndex)
                                return
                            }

                            ///Fallback option
                            if (!hasStrangeAudioTrack && hasElementWithoutLabel && index == groupIndex) {
                                setAudioTrack(rendererIndex, groupIndex, groupElementIndex)
                                return
                            }
                            ///Fallback option
                            if (hasStrangeAudioTrack && name == label) {
                                setAudioTrack(rendererIndex, groupIndex, groupElementIndex)
                                return
                            }
                        }
                    }
                }
            }
        } catch (exception: Exception) {
            Log.e(TAG, "setAudioTrack failed$exception")
        }
    }

    private fun setAudioTrack(rendererIndex: Int, groupIndex: Int, groupElementIndex: Int) {
        val mappedTrackInfo = trackSelector.currentMappedTrackInfo
        if (mappedTrackInfo != null) {
            val builder = trackSelector.parameters.buildUpon()
                .setRendererDisabled(rendererIndex, false)
                .setTrackSelectionOverrides(
                    TrackSelectionOverrides.Builder().addOverride(
                        TrackSelectionOverrides.TrackSelectionOverride(
                            mappedTrackInfo.getTrackGroups(
                                rendererIndex
                            ).get(groupIndex)
                        )
                    ).build()
                )

            trackSelector.setParameters(builder)
        }
    }

    private fun sendSeekToEvent(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        val event: MutableMap<String, Any> = HashMap()
        event["event"] = "seek"
        event["position"] = positionMs
        eventSink.success(event)
    }

    fun setMixWithOthers(mixWithOthers: Boolean) {
        setAudioAttributes(exoPlayer, mixWithOthers)
    }

    override fun dispose() {
        disposePlayer()
    }


    fun disposePlayer() {
        eventChannel.setStreamHandler(null)
        adsLoader?.setPlayer(null)
        playerView?.player = null
        surface?.release()
        exoPlayer?.release()
        exoPlayer = null
        handler.removeCallbacks{}
        disposeMediaSession()
        disposeRemoteNotifications()
        if (isInitialized) {
            exoPlayer?.stop()
        }
        textureEntry.release()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val that = other as BetterPlayer
        if (if (exoPlayer != null) exoPlayer != that.exoPlayer else that.exoPlayer != null) return false
        return if (surface != null) surface == that.surface else that.surface == null
    }

    override fun hashCode(): Int {
        var result = exoPlayer?.hashCode() ?: 0
        result = 31 * result + if (surface != null) surface.hashCode() else 0
        return result
    }

    companion object {
        private const val TAG = "BetterPlayer"
        private const val FORMAT_SS = "ss"
        private const val FORMAT_DASH = "dash"
        private const val FORMAT_HLS = "hls"
        private const val FORMAT_OTHER = "other"
        private const val DEFAULT_NOTIFICATION_CHANNEL = "BETTER_PLAYER_NOTIFICATION"
        private const val NOTIFICATION_ID = 20772077

        //Clear cache without accessing BetterPlayerCache.
        fun clearCache(context: Context?, result: MethodChannel.Result) {
            try {
                context?.let { context ->
                    val file = File(context.cacheDir, "betterPlayerCache")
                    deleteDirectory(file)
                }
                result.success(null)
            } catch (exception: Exception) {
                Log.e(TAG, exception.toString())
                result.error("", "", "")
            }
        }

        private fun deleteDirectory(file: File) {
            if (file.isDirectory) {
                val entries = file.listFiles()
                if (entries != null) {
                    for (entry in entries) {
                        deleteDirectory(entry)
                    }
                }
            }
            if (!file.delete()) {
                Log.e(TAG, "Failed to delete cache dir.")
            }
        }

        //Start pre cache of video. Invoke work manager job and start caching in background.
        fun preCache(
            context: Context?, dataSource: String?, preCacheSize: Long,
            maxCacheSize: Long, maxCacheFileSize: Long, headers: Map<String, String?>,
            cacheKey: String?, result: MethodChannel.Result
        ) {
            try{
                val dataBuilder = Data.Builder()
                    .putString(BetterPlayerPlugin.URL_PARAMETER, dataSource)
                    .putLong(BetterPlayerPlugin.PRE_CACHE_SIZE_PARAMETER, preCacheSize)
                    .putLong(BetterPlayerPlugin.MAX_CACHE_SIZE_PARAMETER, maxCacheSize)
                    .putLong(BetterPlayerPlugin.MAX_CACHE_FILE_SIZE_PARAMETER, maxCacheFileSize)
                if (cacheKey != null) {
                    dataBuilder.putString(BetterPlayerPlugin.CACHE_KEY_PARAMETER, cacheKey)
                }
                for (headerKey in headers.keys) {
                    dataBuilder.putString(
                        BetterPlayerPlugin.HEADER_PARAMETER + headerKey,
                        headers[headerKey]
                    )
                }
                if (dataSource != null && context != null) {
                    val cacheWorkRequest = OneTimeWorkRequest.Builder(CacheWorker::class.java)
                        .addTag(dataSource)
                        .setInputData(dataBuilder.build()).build()
                    WorkManager.getInstance(context).enqueue(cacheWorkRequest)
                }
                result.success(null)
            }catch (e:Exception){
                Log.e("Error pre cache", e.message.toString())
                result.error("","","")
            }
        }

        //Stop pre cache of video with given url. If there's no work manager job for given url, then
        //it will be ignored.
        fun stopPreCache(context: Context?, url: String?, result: MethodChannel.Result) {
            try{
                if (url != null && context != null) {
                    WorkManager.getInstance(context).cancelAllWorkByTag(url)
                }
                result.success(null)
            }catch (e:Exception){
                Log.e("Error stop cache", e.message.toString())
                result.error("","","")
            }
        }
    }

}