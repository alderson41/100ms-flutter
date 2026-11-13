package live.hms.hmssdk_flutter.views;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import hms.webrtc.EglBase;
import hms.webrtc.GlRectDrawer;
import hms.webrtc.RendererCommon;
import hms.webrtc.SurfaceEglRenderer;
import io.flutter.plugin.common.EventChannel;
import io.flutter.view.TextureRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import kotlin.Unit;
import live.hms.video.media.tracks.HMSVideoTrack;
import live.hms.video.utils.SharedEglContext;
import live.hms.videoview.VideoViewStateChangeListener;
import live.hms.videoview.common.VideoTrackManager;

/** Owns one Flutter-managed output surface and its WebRTC renderer. */
public final class HMSTextureView implements EventChannel.StreamHandler,
        TextureRegistry.SurfaceProducer.Callback {
    private static final String TAG = "HMSTextureView";
    private static final ExecutorService CLEANUP = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HMS-texture-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    private final Handler main = new Handler(Looper.getMainLooper());
    private final VideoTrackManager tracks;
    private final List<Consumer<Boolean>> disposalCallbacks = new ArrayList<>();
    private TextureRegistry.SurfaceProducer producer;
    private SurfaceEglRenderer renderer;
    private EventChannel channel;
    private EventChannel.EventSink sink;
    private HMSVideoTrack requestedTrack;
    private boolean disableAutoSimulcast;
    private boolean activitySuspended;
    private boolean surfaceAvailable = true;
    private boolean disposed;
    private boolean disposalComplete;
    private boolean disposalSucceeded;
    private int frameWidth = 1;
    private int frameHeight = 1;
    private int surfaceGeneration;

    public HMSTextureView(TextureRegistry.SurfaceProducer producer) {
        this.producer = producer;
        producer.setSize(frameWidth, frameHeight);
        tracks = new VideoTrackManager(
                () -> renderer,
                events -> {
                    initializeRenderer(events);
                    return Unit.INSTANCE;
                },
                () -> {
                    clearFrameIfVisible();
                    return Unit.INSTANCE;
                },
                () -> {
                    clearFrameIfVisible();
                    return Unit.INSTANCE;
                },
                false);
        tracks.setVideoViewStateChangeListener$videoview_release(new VideoViewStateChangeListener() {
            @Override
            public void onResolutionChange(int width, int height) {
                emitResolution(width, height);
            }

            @Override
            public void onFirstFrameRendered() {}
        });
        producer.setCallback(this);
    }

    private boolean canRender() {
        return !disposed && surfaceAvailable && !activitySuspended;
    }

    private void clearFrameIfVisible() {
        if (canRender() && renderer != null) renderer.clearFrame();
    }

    private void initializeRenderer(RendererCommon.RendererEvents events) {
        if (!canRender()) return;
        if (renderer == null) {
            renderer = new SurfaceEglRenderer("HMS-texture-" + producer.id());
            renderer.init(SharedEglContext.INSTANCE.getContext(), new RendererCommon.RendererEvents() {
                @Override
                public void onFirstFrameRendered() {
                    main.post(() -> {
                        if (canRender()) events.onFirstFrameRendered();
                    });
                }

                @Override
                public void onFrameResolutionChanged(int width, int height, int rotation) {
                    int rotatedWidth = rotation % 180 == 0 ? width : height;
                    int rotatedHeight = rotation % 180 == 0 ? height : width;
                    if (rotatedWidth <= 0 || rotatedHeight <= 0) return;
                    main.post(() -> {
                        if (!canRender()) return;
                        if (frameWidth != rotatedWidth || frameHeight != rotatedHeight) {
                            frameWidth = rotatedWidth;
                            frameHeight = rotatedHeight;
                            bindSurface();
                        }
                        events.onFrameResolutionChanged(rotatedWidth, rotatedHeight, 0);
                    });
                }
            }, EglBase.CONFIG_PLAIN, new GlRectDrawer());
        }
        bindSurface();
    }

    /** Detach EGL before resizing/replacing its target; never wait on the UI thread. */
    private void bindSurface() {
        if (!canRender() || renderer == null) return;
        final SurfaceEglRenderer target = renderer;
        final int generation = ++surfaceGeneration;
        target.pauseVideo();
        target.releaseEglSurface(() -> main.post(() -> {
            if (!canRender() || generation != surfaceGeneration || renderer != target
                    || requestedTrack == null) return;
            producer.setSize(frameWidth, frameHeight);
            // Flutter can replace this Surface at resize, resume or memory trim.
            // It owns the Surface; only the producer may release it.
            target.createEglSurface(producer.getSurface());
            target.disableFpsReduction();
        }));
    }

    private void emitResolution(int width, int height) {
        if (disposed || width <= 0 || height <= 0 || sink == null) return;
        Map<String, Object> dimensions = new HashMap<>();
        dimensions.put("width", width);
        dimensions.put("height", height);
        Map<String, Object> event = new HashMap<>();
        event.put("event_name", "on_resolution_changed");
        event.put("data", dimensions);
        sink.success(event);
    }

    public void addTrack(HMSVideoTrack track, boolean disableAutoSimulcastLayerSelect) {
        addTrack(track, disableAutoSimulcastLayerSelect, null, null);
    }

    public void addTrack(HMSVideoTrack track, boolean disableAutoSimulcastLayerSelect,
                         Integer height, Integer width) {
        if (disposed) return;
        requestedTrack = track;
        disableAutoSimulcast = disableAutoSimulcastLayerSelect;
        tracks.disableAutoSimulcastLayerSelect$videoview_release(disableAutoSimulcast);
        if (!disableAutoSimulcast && height != null && width != null) {
            setDisplayResolution(width, height);
        }
        if (canRender()) tracks.addTrack(track, true);
    }

    public void setDisplayResolution(int width, int height) {
        if (!disposed && width > 0 && height > 0) {
            tracks.onSizeChanged$videoview_release(width, height);
        }
    }

    public void removeTrack() {
        requestedTrack = null;
        if (!disposed) tracks.removeTrack$videoview_release();
    }

    /** Hidden Flutter output must not retain a video sink; native PiP is independent. */
    public void setRenderingSuspended(boolean suspended) {
        if (disposed || activitySuspended == suspended) return;
        activitySuspended = suspended;
        updateRendering();
    }

    @Override
    public void onSurfaceCleanup() {
        if (disposed) return;
        surfaceAvailable = false;
        updateRendering();
    }

    @Override
    public void onSurfaceAvailable() {
        if (disposed) return;
        surfaceAvailable = true;
        updateRendering();
    }

    private void updateRendering() {
        if (canRender()) {
            if (requestedTrack != null) {
                tracks.disableAutoSimulcastLayerSelect$videoview_release(disableAutoSimulcast);
                tracks.addTrack(requestedTrack, true);
            }
            return;
        }
        ++surfaceGeneration;
        tracks.removeTrack$videoview_release();
        if (renderer != null) {
            renderer.pauseVideo();
            renderer.releaseEglSurface(() -> {});
        }
    }

    public void disposeTextureView() {
        disposeTextureView(success -> {});
    }

    public void disposeTextureView(Consumer<Boolean> completion) {
        if (disposalComplete) {
            completion.accept(disposalSucceeded);
            return;
        }
        disposalCallbacks.add(completion);
        if (disposed) return;
        disposed = true;
        ++surfaceGeneration;
        requestedTrack = null;
        tracks.removeTrack$videoview_release();
        sink = null;
        if (channel != null) channel.setStreamHandler(null);
        channel = null;
        tracks.setVideoViewStateChangeListener$videoview_release(null);
        SurfaceEglRenderer releasedRenderer = renderer;
        TextureRegistry.SurfaceProducer releasedProducer = producer;
        releasedProducer.setCallback(null);
        renderer = null;
        producer = null;
        // Keep the producer alive until its EGL surface and render thread are gone.
        CLEANUP.execute(() -> {
            boolean success = true;
            try {
                if (releasedRenderer != null) releasedRenderer.release();
            } catch (RuntimeException error) {
                success = false;
                Log.e(TAG, "Native renderer shutdown failed", error);
            }
            final boolean completed = success;
            main.post(() -> {
                boolean producerReleased = completed;
                try {
                    releasedProducer.release();
                } catch (RuntimeException error) {
                    producerReleased = false;
                    Log.e(TAG, "Flutter surface producer shutdown failed", error);
                }
                disposalComplete = true;
                disposalSucceeded = producerReleased;
                List<Consumer<Boolean>> callbacks = new ArrayList<>(disposalCallbacks);
                disposalCallbacks.clear();
                for (Consumer<Boolean> callback : callbacks) {
                    callback.accept(disposalSucceeded);
                }
            });
        });
    }

    /** Complete on the UI thread after all queued EGL and producer releases. */
    public static void afterPendingCleanup(Runnable completion) {
        CLEANUP.execute(() -> new Handler(Looper.getMainLooper()).post(completion));
    }

    public void setTextureViewEventChannel(EventChannel channel) {
        this.channel = channel;
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        if (disposed) return;
        sink = events;
        emitResolution(frameWidth, frameHeight);
    }

    @Override
    public void onCancel(Object arguments) {
        sink = null;
    }
}
