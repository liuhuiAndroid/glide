package com.bumptech.glide.load.engine;

import android.util.Log;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.engine.executor.Prioritized;
import com.bumptech.glide.request.ResourceCallback;

/**
 * A runnable class responsible for using an {@link com.bumptech.glide.load.engine.DecodeJob} to decode resources on a
 * background thread in two stages.
 *
 * <p>
 *     In the first stage, this class attempts to decode a resource
 *     from cache, first using transformed data and then using source data. If no resource can be decoded from cache,
 *     this class then requests to be posted again. During the second stage this class then attempts to use the
 *     {@link com.bumptech.glide.load.engine.DecodeJob} to decode data directly from the original source.
 * </p>
 *
 * <p>
 *     Using two stages with a re-post in between allows us to run fast disk cache decodes on one thread and slow source
 *     fetches on a second pool so that loads for local data are never blocked waiting for loads for remote data to
 *     complete.
 * </p>
 */
class EngineRunnable implements Runnable, Prioritized {
    private static final String TAG = "EngineRunnable";

    private final Priority priority;
    private final EngineRunnableManager manager;
    private final DecodeJob<?, ?, ?> decodeJob;

    private Stage stage;

    private volatile boolean isCancelled;

    public EngineRunnable(EngineRunnableManager manager, DecodeJob<?, ?, ?> decodeJob, Priority priority) {
        this.manager = manager;
        this.decodeJob = decodeJob;
        this.stage = Stage.CACHE;
        this.priority = priority;
    }

    public void cancel() {
        isCancelled = true;
        decodeJob.cancel();
    }

    @Override
    public void run() {
        if (isCancelled) {
            return;
        }

        Exception exception = null;
        Resource<?> resource = null;
        try {
            // 编码
            // 调用了一个decode()方法，并且这个方法返回了一个Resource对象。
            // 进去看看
            resource = decode();
            // 绕一圈回来了，接下来就是如何将它显示出来了
        } catch (Exception e) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "Exception decoding", e);
            }
            exception = e;
        }

        if (isCancelled) {
            if (resource != null) {
                resource.recycle();
            }
            return;
        }

        if (resource == null) {
            onLoadFailed(exception);
        } else {
            // 表示图片加载已经完成了,进去
            onLoadComplete(resource);
        }
    }

    private boolean isDecodingFromCache() {
        return stage == Stage.CACHE;
    }

    private void onLoadComplete(Resource resource) {
        // 这个manager就是EngineJob对象，因此这里实际上调用的是EngineJob的onResourceReady()方法
        manager.onResourceReady(resource);
    }

    private void onLoadFailed(Exception e) {
        if (isDecodingFromCache()) {
            stage = Stage.SOURCE;
            manager.submitForSource(this);
        } else {
            manager.onException(e);
        }
    }

    /**
     * 分了两种情况，
     * 从缓存当中去decode图片的话就会执行decodeFromCache()，
     * 否则的话就执行decodeFromSource()
     */
    private Resource<?> decode() throws Exception {
        if (isDecodingFromCache()) {
            // 从硬盘缓存当中读取图片
            return decodeFromCache();
        } else {
            // 进去看看，不讨论缓存的情况
            return decodeFromSource();
        }
    }

    private Resource<?> decodeFromCache() throws Exception {
        Resource<?> result = null;
        try {
            // 获取缓存 DiskCacheStrategy.RESULT,表示只缓存转换过后的图片
            result = decodeJob.decodeResultFromCache();
        } catch (Exception e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Exception decoding result from cache: " + e);
            }
        }

        if (result == null) {
            // 获取不到，会再调用decodeSourceFromCache()方法获取缓存 DiskCacheStrategy.SOURCE,表示只缓存原始图片
            result = decodeJob.decodeSourceFromCache();
        }
        return result;
    }

    private Resource<?> decodeFromSource() throws Exception {
        //调用了DecodeJob的decodeFromSource()方法。DecodeJob的任务十分繁重,进去看看
        return decodeJob.decodeFromSource();
    }

    @Override
    public int getPriority() {
        return priority.ordinal();
    }

    private enum Stage {
        /** Attempting to decode resource from cache. */
        CACHE,
        /** Attempting to decode resource from source data. */
        SOURCE
    }

    interface EngineRunnableManager extends ResourceCallback {
        void submitForSource(EngineRunnable runnable);
    }
}
