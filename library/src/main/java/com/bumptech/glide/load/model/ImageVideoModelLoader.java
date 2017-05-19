package com.bumptech.glide.load.model;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.data.DataFetcher;

import java.io.InputStream;

/**
 * A wrapper model loader that provides both an {@link java.io.InputStream} and a
 * {@link android.os.ParcelFileDescriptor} for a given model type by wrapping an
 * {@link com.bumptech.glide.load.model.ModelLoader} for {@link java.io.InputStream}s for the given model type and an
 * {@link com.bumptech.glide.load.model.ModelLoader} for {@link android.os.ParcelFileDescriptor} for the given model
 * type.
 *
 * @param <A> The model type.
 */
public class ImageVideoModelLoader<A> implements ModelLoader<A, ImageVideoWrapper> {
    private static final String TAG = "IVML";

    private final ModelLoader<A, InputStream> streamLoader;
    private final ModelLoader<A, ParcelFileDescriptor> fileDescriptorLoader;

    public ImageVideoModelLoader(ModelLoader<A, InputStream> streamLoader,
            ModelLoader<A, ParcelFileDescriptor> fileDescriptorLoader) {
        if (streamLoader == null && fileDescriptorLoader == null) {
            throw new NullPointerException("At least one of streamLoader and fileDescriptorLoader must be non null");
        }
        this.streamLoader = streamLoader;
        this.fileDescriptorLoader = fileDescriptorLoader;
    }

    @Override
    public DataFetcher<ImageVideoWrapper> getResourceFetcher(A model, int width, int height) {
        DataFetcher<InputStream> streamFetcher = null;
        if (streamLoader != null) {
            streamFetcher = streamLoader.getResourceFetcher(model, width, height);
        }
        DataFetcher<ParcelFileDescriptor> fileDescriptorFetcher = null;
        if (fileDescriptorLoader != null) {
            // 先调用streamLoader.getResourceFetcher()方法获取一个DataFetcher
            //而这个streamLoader其实就是我们在loadGeneric()方法中构建出的StreamStringLoader，
            // 调用它的getResourceFetcher()方法会得到一个HttpUrlFetcher对象
            fileDescriptorFetcher = fileDescriptorLoader.getResourceFetcher(model, width, height);
        }

        if (streamFetcher != null || fileDescriptorFetcher != null) {
            // new出了一个ImageVideoFetcher对象，并把获得的HttpUrlFetcher对象传进去
            // 也就是说，ImageVideoModelLoader的getResourceFetcher()方法得到的是一个ImageVideoFetcher。
            return new ImageVideoFetcher(streamFetcher, fileDescriptorFetcher);
        } else {
            return null;
        }
    }

    static class ImageVideoFetcher implements DataFetcher<ImageVideoWrapper> {
        private final DataFetcher<InputStream> streamFetcher;
        private final DataFetcher<ParcelFileDescriptor> fileDescriptorFetcher;

        public ImageVideoFetcher(DataFetcher<InputStream> streamFetcher,
                DataFetcher<ParcelFileDescriptor> fileDescriptorFetcher) {
            this.streamFetcher = streamFetcher;
            this.fileDescriptorFetcher = fileDescriptorFetcher;
        }

        @SuppressWarnings("resource")
        // @see ModelLoader.loadData
        @Override
        public ImageVideoWrapper loadData(Priority priority) throws Exception {
            InputStream is = null;
            if (streamFetcher != null) {
                try {
                    // streamFetcher是什么呢？
                    // 自然就是刚才在组装ImageVideoFetcher对象时传进来的HttpUrlFetcher了。
                    // 因此这里又会去调用HttpUrlFetcher的loadData()方法
                    is = streamFetcher.loadData(priority);
                } catch (Exception e) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Exception fetching input stream, trying ParcelFileDescriptor", e);
                    }
                    if (fileDescriptorFetcher == null) {
                        throw e;
                    }
                }
            }
            ParcelFileDescriptor fileDescriptor = null;
            if (fileDescriptorFetcher != null) {
                try {
                    fileDescriptor = fileDescriptorFetcher.loadData(priority);
                } catch (Exception e) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Exception fetching ParcelFileDescriptor", e);
                    }
                    if (is == null) {
                        throw e;
                    }
                }
            }
            // 创建了一个ImageVideoWrapper对象，并把刚才得到的InputStream作为参数传了进去
            return new ImageVideoWrapper(is, fileDescriptor);
        }

        @Override
        public void cleanup() {
            //TODO: what if this throws?
            if (streamFetcher != null) {
                streamFetcher.cleanup();
            }
            if (fileDescriptorFetcher != null) {
                fileDescriptorFetcher.cleanup();
            }
        }

        @Override
        public String getId() {
            // Both the stream fetcher and the file descriptor fetcher should return the same id.
            if (streamFetcher != null) {
                return streamFetcher.getId();
            } else {
                return fileDescriptorFetcher.getId();
            }
        }

        @Override
        public void cancel() {
            if (streamFetcher != null) {
                streamFetcher.cancel();
            }
            if (fileDescriptorFetcher != null) {
                fileDescriptorFetcher.cancel();
            }
        }
    }
}
