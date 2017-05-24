package com.bumptech.glide.load.engine;

import android.os.Looper;
import android.os.MessageQueue;
import android.util.Log;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.engine.cache.DiskCache;
import com.bumptech.glide.load.engine.cache.DiskCacheAdapter;
import com.bumptech.glide.load.engine.cache.MemoryCache;
import com.bumptech.glide.load.resource.transcode.ResourceTranscoder;
import com.bumptech.glide.provider.DataLoadProvider;
import com.bumptech.glide.request.ResourceCallback;
import com.bumptech.glide.util.LogTime;
import com.bumptech.glide.util.Util;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Responsible for starting loads and managing active and cached resources.
 */
public class Engine implements EngineJobListener,
        MemoryCache.ResourceRemovedListener,
        EngineResource.ResourceListener {
    private static final String TAG = "Engine";
    private final Map<Key, EngineJob> jobs;
    private final EngineKeyFactory keyFactory;
    private final MemoryCache cache;
    private final EngineJobFactory engineJobFactory;
    private final Map<Key, WeakReference<EngineResource<?>>> activeResources;
    private final ResourceRecycler resourceRecycler;
    private final LazyDiskCacheProvider diskCacheProvider;

    // Lazily instantiate to avoid exceptions if Glide is initialized on a background thread. See #295.
    private ReferenceQueue<EngineResource<?>> resourceReferenceQueue;

    /**
     * Allows a request to indicate it no longer is interested in a given load.
     */
    public static class LoadStatus {
        private final EngineJob engineJob;
        private final ResourceCallback cb;

        public LoadStatus(ResourceCallback cb, EngineJob engineJob) {
            this.cb = cb;
            this.engineJob = engineJob;
        }

        public void cancel() {
            engineJob.removeCallback(cb);
        }
    }

    public Engine(MemoryCache memoryCache, DiskCache.Factory diskCacheFactory, ExecutorService diskCacheService,
            ExecutorService sourceService) {
        this(memoryCache, diskCacheFactory, diskCacheService, sourceService, null, null, null, null, null);
    }

    // Visible for testing.
    Engine(MemoryCache cache, DiskCache.Factory diskCacheFactory, ExecutorService diskCacheService,
            ExecutorService sourceService, Map<Key, EngineJob> jobs, EngineKeyFactory keyFactory,
            Map<Key, WeakReference<EngineResource<?>>> activeResources, EngineJobFactory engineJobFactory,
            ResourceRecycler resourceRecycler) {
        this.cache = cache;
        this.diskCacheProvider = new LazyDiskCacheProvider(diskCacheFactory);

        if (activeResources == null) {
            activeResources = new HashMap<Key, WeakReference<EngineResource<?>>>();
        }
        this.activeResources = activeResources;

        if (keyFactory == null) {
            keyFactory = new EngineKeyFactory();
        }
        this.keyFactory = keyFactory;

        if (jobs == null) {
            jobs = new HashMap<Key, EngineJob>();
        }
        this.jobs = jobs;

        if (engineJobFactory == null) {
            engineJobFactory = new EngineJobFactory(diskCacheService, sourceService, this);
        }
        this.engineJobFactory = engineJobFactory;

        if (resourceRecycler == null) {
            resourceRecycler = new ResourceRecycler();
        }
        this.resourceRecycler = resourceRecycler;

        cache.setResourceRemovedListener(this);
    }

    /**
     * Starts a load for the given arguments. Must be called on the main thread.
     *
     * <p>
     *     The flow for any request is as follows:
     *     <ul>
     *         <li>Check the memory cache and provide the cached resource if present</li>
     *         <li>Check the current set of actively used resources and return the active resource if present</li>
     *         <li>Check the current set of in progress loads and add the cb to the in progress load if present</li>
     *         <li>Start a new load</li>
     *     </ul>
     * </p>
     *
     * <p>
     *     Active resources are those that have been provided to at least one request and have not yet been released.
     *     Once all consumers of a resource have released that resource, the resource then goes to cache. If the
     *     resource is ever returned to a new consumer from cache, it is re-added to the active resources. If the
     *     resource is evicted from the cache, its resources are recycled and re-used if possible and the resource is
     *     discarded. There is no strict requirement that consumers release their resources so active resources are
     *     held weakly.
     * </p>
     *
     * @param signature A non-null unique key to be mixed into the cache key that identifies the version of the data to
     *                  be loaded.
     * @param width The target width in pixels of the desired resource.
     * @param height The target height in pixels of the desired resource.
     * @param fetcher The fetcher to use to retrieve data not in the disk cache.
     * @param loadProvider The load provider containing various encoders and decoders use to decode and encode data.
     * @param transformation The transformation to use to transform the decoded resource.
     * @param transcoder The transcoder to use to transcode the decoded and transformed resource.
     * @param priority The priority with which the request should run.
     * @param isMemoryCacheable True if the transcoded resource can be cached in memory.
     * @param diskCacheStrategy The strategy to use that determines what type of data, if any,
     *                          will be cached in the local disk cache.
     * @param cb The callback that will be called when the load completes.
     *
     * @param <T> The type of data the resource will be decoded from.
     * @param <Z> The type of the resource that will be decoded.
     * @param <R> The type of the resource that will be transcoded from the decoded resource.
     * 这个方法的代码有点长，但大多数的代码都是在处理缓存的
     */
    public <T, Z, R> LoadStatus load(Key signature, int width, int height, DataFetcher<T> fetcher,
            DataLoadProvider<T, Z> loadProvider, Transformation<Z> transformation, ResourceTranscoder<Z, R> transcoder,
            Priority priority, boolean isMemoryCacheable, DiskCacheStrategy diskCacheStrategy, ResourceCallback cb) {
        Util.assertMainThread();
        long startTime = LogTime.getLogTime();

        // 获得了一个id字符串，这个字符串也就是我们要加载的图片的唯一标识，这个id其实就是图片的url地址
        // fetcher就是HttpUrlFetcher的实例
        final String id = fetcher.getId();

        // 构建出了一个EngineKey对象，这个EngineKey也就是Glide中的缓存Key
        EngineKey key = keyFactory.buildKey(id, signature, width, height, loadProvider.getCacheDecoder(),
                loadProvider.getSourceDecoder(), transformation, loadProvider.getEncoder(),
                transcoder, loadProvider.getSourceEncoder());


        // 方法一
        // 先从cache中寻找资源,cache是LruResourceCache对象，作为资源的LRU缓存
        EngineResource<?> cached = loadFromCache(key, isMemoryCacheable);
        if (cached != null) {
            // 如果获取到就直接调用cb.onResourceReady()方法进行回调
            cb.onResourceReady(cached);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                logWithTimeAndKey("Loaded resource from cache", startTime, key);
            }
            return null;
        }

        // 方法二
        // 从activeResources中寻找
        // activeResources是以弱引用为值的Map，用于缓存使用中的资源
        // 比一般内存缓存额外多一级缓存的意义在于，当内存不足时清理cache中的资源时，不会对使用中的Bitmap造成影响。
        EngineResource<?> active = loadFromActiveResources(key, isMemoryCacheable);
        if (active != null) {
            // 获取到的话也直接进行回调
            cb.onResourceReady(active);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                logWithTimeAndKey("Loaded resource from active resources", startTime, key);
            }
            return null;
        }

        EngineJob current = jobs.get(key);
        if (current != null) {
            current.addCallback(cb);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                logWithTimeAndKey("Added to existing load", startTime, key);
            }
            return new LoadStatus(cb, current);
        }

        // cache中找不到 // 只有在上面两个方法都没有获取到缓存的情况下，才会继续向下执行，从而开启线程来加载图片。
        // 这里构建了一个EngineJob，它的主要作用就是用来开启线程的，为后面的异步加载图片做准备。
        EngineJob engineJob = engineJobFactory.build(key, isMemoryCacheable);
        // 创建了一个DecodeJob对象，从名字上来看，它好像是用来对图片进行解码的，但实际上它的任务十分繁重，待会我们就知道了
        DecodeJob<T, Z, R> decodeJob = new DecodeJob<T, Z, R>(key, width, height, fetcher, loadProvider, transformation,
                transcoder, diskCacheProvider, diskCacheStrategy, priority);
        // 创建了一个EngineRunnable对象，并且在下面调用了EngineJob的start()方法来运行EngineRunnable对象，
        // 这实际上就是让EngineRunnable的run()方法在子线程当中执行了。// 进去run方法看看
        EngineRunnable runnable = new EngineRunnable(engineJob, decodeJob, priority);
        jobs.put(key, engineJob);
        // 就是在这里调用的EngineJob的addCallback()方法来注册的一个ResourceCallback
        engineJob.addCallback(cb);
        engineJob.start(runnable);

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Started new load", startTime, key);
        }
        return new LoadStatus(cb, engineJob);
    }

    private static void logWithTimeAndKey(String log, long startTime, Key key) {
        Log.v(TAG, log + " in " + LogTime.getElapsedMillis(startTime) + "ms, key: " + key);
    }

    private EngineResource<?> loadFromActiveResources(Key key, boolean isMemoryCacheable) {
        if (!isMemoryCacheable) {
            return null;
        }

        // 从activeResources这个HashMap当中取值的。
        // 使用activeResources来缓存正在使用中的图片，可以保护这些图片不会被LruCache算法回收掉。
        EngineResource<?> active = null;
        WeakReference<EngineResource<?>> activeRef = activeResources.get(key);
        if (activeRef != null) {
            active = activeRef.get();
            if (active != null) {
                active.acquire();
            } else {
                activeResources.remove(key);
            }
        }

        return active;
    }

    private EngineResource<?> loadFromCache(Key key, boolean isMemoryCacheable) {
        if (!isMemoryCacheable) {
            // skipMemoryCache()方法，如果在这个方法中传入true，那么这里的isMemoryCacheable就会是false,
            // 表示内存缓存已被禁用
            return null;
        }

        // 调用了getEngineResourceFromCache()方法来获取缓存
        EngineResource<?> cached = getEngineResourceFromCache(key);
        if (cached != null) {
            cached.acquire();
            // 将这个缓存图片存储到activeResources当中,activeResources就是一个弱引用的HashMap，用来缓存正在使用中的图片
            activeResources.put(key, new ResourceWeakReference(key, cached, getReferenceQueue()));
        }
        return cached;
    }

    @SuppressWarnings("unchecked")
    private EngineResource<?> getEngineResourceFromCache(Key key) {
        // cache对象就是在构建Glide对象时创建的LruResourceCache，
        // 那么说明这里其实使用的就是LruCache算法了

        //获取到缓存图片之后会将它从缓存中移除
        Resource<?> cached = cache.remove(key);

        final EngineResource result;
        if (cached == null) {
            result = null;
        } else if (cached instanceof EngineResource) {
            // Save an object allocation if we've cached an EngineResource (the typical case).
            result = (EngineResource) cached;
        } else {
            result = new EngineResource(cached, true /*isCacheable*/);
        }
        return result;
    }

    public void release(Resource resource) {
        Util.assertMainThread();
        if (resource instanceof EngineResource) {
            ((EngineResource) resource).release();
        } else {
            throw new IllegalArgumentException("Cannot release anything but an EngineResource");
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onEngineJobComplete(Key key, EngineResource<?> resource) {
        Util.assertMainThread();
        // A null resource indicates that the load failed, usually due to an exception.
        if (resource != null) {
            resource.setResourceListener(key, this);

            if (resource.isCacheable()) {
                // EngineJob中onEngineJobComplete回调过来的EngineResource被put到了activeResources当中，
                // 也就是在这里写入的弱引用缓存
                activeResources.put(key, new ResourceWeakReference(key, resource, getReferenceQueue()));
            }
        }
        // TODO: should this check that the engine job is still current?
        jobs.remove(key);
    }

    @Override
    public void onEngineJobCancelled(EngineJob engineJob, Key key) {
        Util.assertMainThread();
        EngineJob current = jobs.get(key);
        if (engineJob.equals(current)) {
            jobs.remove(key);
        }
    }

    @Override
    public void onResourceRemoved(final Resource<?> resource) {
        Util.assertMainThread();
        resourceRecycler.recycle(resource);
    }

    /**
     * 实现了正在使用中的图片使用弱引用来进行缓存，不在使用中的图片使用LruCache来进行缓存的功能。
     */
    @Override
    public void onResourceReleased(Key cacheKey, EngineResource resource) {
        Util.assertMainThread();
        // 首先会将缓存图片从activeResources中移除
        activeResources.remove(cacheKey);
        if (resource.isCacheable()) {
            // 然后再将它put到LruResourceCache当中
            cache.put(cacheKey, resource);
        } else {
            resourceRecycler.recycle(resource);
        }
    }

    public void clearDiskCache() {
        diskCacheProvider.getDiskCache().clear();
    }

    private ReferenceQueue<EngineResource<?>> getReferenceQueue() {
        if (resourceReferenceQueue == null) {
            resourceReferenceQueue = new ReferenceQueue<EngineResource<?>>();
            MessageQueue queue = Looper.myQueue();
            queue.addIdleHandler(new RefQueueIdleHandler(activeResources, resourceReferenceQueue));
        }
        return resourceReferenceQueue;
    }

    private static class LazyDiskCacheProvider implements DecodeJob.DiskCacheProvider {

        private final DiskCache.Factory factory;
        private volatile DiskCache diskCache;

        public LazyDiskCacheProvider(DiskCache.Factory factory) {
            this.factory = factory;
        }

        @Override
        public DiskCache getDiskCache() {
            if (diskCache == null) {
                synchronized (this) {
                    if (diskCache == null) {
                        diskCache = factory.build();
                    }
                    if (diskCache == null) {
                        diskCache = new DiskCacheAdapter();
                    }
                }
            }
            return diskCache;
        }
    }

    private static class ResourceWeakReference extends WeakReference<EngineResource<?>> {
        private final Key key;

        public ResourceWeakReference(Key key, EngineResource<?> r, ReferenceQueue<? super EngineResource<?>> q) {
            super(r, q);
            this.key = key;
        }
    }

    // Responsible for cleaning up the active resource map by remove weak references that have been cleared.
    private static class RefQueueIdleHandler implements MessageQueue.IdleHandler {
        private final Map<Key, WeakReference<EngineResource<?>>> activeResources;
        private final ReferenceQueue<EngineResource<?>> queue;

        public RefQueueIdleHandler(Map<Key, WeakReference<EngineResource<?>>> activeResources,
                ReferenceQueue<EngineResource<?>> queue) {
            this.activeResources = activeResources;
            this.queue = queue;
        }

        @Override
        public boolean queueIdle() {
            ResourceWeakReference ref = (ResourceWeakReference) queue.poll();
            if (ref != null) {
                activeResources.remove(ref.key);
            }

            return true;
        }
    }

    // Visible for testing.
    static class EngineJobFactory {
        private final ExecutorService diskCacheService;
        private final ExecutorService sourceService;
        private final EngineJobListener listener;

        public EngineJobFactory(ExecutorService diskCacheService, ExecutorService sourceService,
                EngineJobListener listener) {
            this.diskCacheService = diskCacheService;
            this.sourceService = sourceService;
            this.listener = listener;
        }

        public EngineJob build(Key key, boolean isMemoryCacheable) {
            return new EngineJob(key, diskCacheService, sourceService, isMemoryCacheable, listener);
        }
    }
}
