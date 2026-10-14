package live.hms.hmssdk_flutter.views;

import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import hms.webrtc.EglBase;
import hms.webrtc.GlRectDrawer;
import hms.webrtc.RendererCommon;
import hms.webrtc.SurfaceEglRenderer;
import io.flutter.plugin.common.EventChannel;
import io.flutter.view.TextureRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import kotlin.Unit;
import live.hms.video.media.tracks.HMSVideoTrack;
import live.hms.video.utils.SharedEglContext;
import live.hms.videoview.VideoViewStateChangeListener;
import live.hms.videoview.common.VideoTrackManager;

/** Texture ownership for the pinned video-view SDK, including full EGL shutdown. */
public final class HMSTextureView implements EventChannel.StreamHandler {
    private static final String TAG = "HMSTextureView";
    private static final ExecutorService CLEANUP = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HMS-texture-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SurfaceTexture texture;
    private final VideoTrackManager tracks;
    private TextureRegistry.SurfaceTextureEntry entry;
    private SurfaceEglRenderer renderer;
    private EventChannel channel;
    private EventChannel.EventSink sink;
    private boolean disposed;

    public HMSTextureView(SurfaceTexture texture, TextureRegistry.SurfaceTextureEntry entry) {
        this.texture = texture;
        this.entry = entry;
        // Retain the SDK's track binding and automatic layer selection policies.
        // Java accesses the pinned library's JVM names without private reflection.
        tracks = new VideoTrackManager(
                () -> renderer,
                events -> {
                    initializeRenderer(events);
                    return Unit.INSTANCE;
                },
                () -> {
                    // A detached track can return. Keep this texture's renderer
                    // until disposal, instead of repeatedly releasing its surface.
                    if (renderer != null) renderer.clearFrame();
                    return Unit.INSTANCE;
                },
                () -> {
                    if (renderer != null) renderer.clearFrame();
                    return Unit.INSTANCE;
                },
                false);
        tracks.setVideoViewStateChangeListener$videoview_release(new VideoViewStateChangeListener() {
            @Override
            public void onResolutionChange(int width, int height) {
                if (disposed || width <= 0 || height <= 0 || sink == null) return;
                Map<String, Object> dimensions = new HashMap<>();
                dimensions.put("width", width);
                dimensions.put("height", height);
                Map<String, Object> event = new HashMap<>();
                event.put("event_name", "on_resolution_changed");
                event.put("data", dimensions);
                sink.success(event);
            }

            @Override
            public void onFirstFrameRendered() {}
        });
    }

    private void initializeRenderer(RendererCommon.RendererEvents events) {
        if (renderer != null || disposed) return;
        renderer = new SurfaceEglRenderer("HMS-texture-" + entry.id());
        renderer.init(SharedEglContext.INSTANCE.getContext(), new RendererCommon.RendererEvents() {
            @Override
            public void onFirstFrameRendered() {
                main.post(() -> {
                    if (!disposed) events.onFirstFrameRendered();
                });
            }

            @Override
            public void onFrameResolutionChanged(int width, int height, int rotation) {
                int rotatedWidth = rotation % 180 == 0 ? width : height;
                int rotatedHeight = rotation % 180 == 0 ? height : width;
                if (rotatedWidth <= 0 || rotatedHeight <= 0) return;
                main.post(() -> {
                    if (disposed) return;
                    texture.setDefaultBufferSize(rotatedWidth, rotatedHeight);
                    events.onFrameResolutionChanged(rotatedWidth, rotatedHeight, rotation);
                });
            }
        }, EglBase.CONFIG_PLAIN, new GlRectDrawer());
        renderer.createEglSurface(texture);
    }

    public void addTrack(HMSVideoTrack track, boolean disableAutoSimulcastLayerSelect) {
        addTrack(track, disableAutoSimulcastLayerSelect, null, null);
    }

    public void addTrack(HMSVideoTrack track, boolean disableAutoSimulcastLayerSelect,
                         Integer height, Integer width) {
        if (disposed) return;
        tracks.disableAutoSimulcastLayerSelect$videoview_release(disableAutoSimulcastLayerSelect);
        if (!disableAutoSimulcastLayerSelect && height != null && width != null) {
            setDisplayResolution(width, height);
        }
        tracks.addTrack(track, true);
    }

    public void setDisplayResolution(int width, int height) {
        if (!disposed && width > 0 && height > 0) {
            tracks.onSizeChanged$videoview_release(width, height);
        }
    }

    public void removeTrack() {
        if (!disposed) tracks.removeTrack$videoview_release();
    }

    public void disposeTextureView() {
        disposeTextureView(success -> {});
    }

    public void disposeTextureView(Consumer<Boolean> completion) {
        if (disposed) {
            completion.accept(true);
            return;
        }
        removeTrack();
        disposed = true;
        sink = null;
        if (channel != null) channel.setStreamHandler(null);
        channel = null;
        tracks.setVideoViewStateChangeListener$videoview_release(null);
        SurfaceEglRenderer releasedRenderer = renderer;
        TextureRegistry.SurfaceTextureEntry releasedEntry = entry;
        renderer = null;
        entry = null;
        // release() shuts down the render thread and frees all EGL resources.
        // Its wait belongs off the UI thread; the texture outlives that cleanup.
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
                if (releasedEntry != null) releasedEntry.release();
                completion.accept(completed);
            });
        });
    }

    /** Complete on the UI thread after all queued EGL and texture releases. */
    public static void afterPendingCleanup(Runnable completion) {
        CLEANUP.execute(() -> new Handler(Looper.getMainLooper()).post(completion));
    }

    public void setTextureViewEventChannel(EventChannel channel) {
        this.channel = channel;
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        if (!disposed) sink = events;
    }

    @Override
    public void onCancel(Object arguments) {
        sink = null;
    }
}
