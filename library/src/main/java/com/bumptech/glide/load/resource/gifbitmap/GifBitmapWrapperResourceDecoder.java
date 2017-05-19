package com.bumptech.glide.load.resource.gifbitmap;

import android.graphics.Bitmap;

import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.model.ImageVideoWrapper;
import com.bumptech.glide.load.resource.bitmap.BitmapResource;
import com.bumptech.glide.load.resource.bitmap.ImageHeaderParser;
import com.bumptech.glide.load.resource.bitmap.RecyclableBufferedInputStream;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import com.bumptech.glide.util.ByteArrayPool;

import java.io.IOException;
import java.io.InputStream;

/**
 * An {@link ResourceDecoder} that can decode either an {@link Bitmap} or an {@link GifDrawable}
 * from an {@link InputStream} or a {@link android.os.ParcelFileDescriptor ParcelFileDescriptor}.
 */
public class GifBitmapWrapperResourceDecoder implements ResourceDecoder<ImageVideoWrapper, GifBitmapWrapper> {
    private static final ImageTypeParser DEFAULT_PARSER = new ImageTypeParser();
    private static final BufferedStreamFactory DEFAULT_STREAM_FACTORY = new BufferedStreamFactory();
    // 2048 is rather arbitrary, for most well formatted image types we only need 32 bytes.
    // Visible for testing.
    static final int MARK_LIMIT_BYTES = 2048;

    private final ResourceDecoder<ImageVideoWrapper, Bitmap> bitmapDecoder;
    private final ResourceDecoder<InputStream, GifDrawable> gifDecoder;
    private final BitmapPool bitmapPool;
    private final ImageTypeParser parser;
    private final BufferedStreamFactory streamFactory;
    private String id;

    public GifBitmapWrapperResourceDecoder(ResourceDecoder<ImageVideoWrapper, Bitmap> bitmapDecoder,
            ResourceDecoder<InputStream, GifDrawable> gifDecoder, BitmapPool bitmapPool) {
        this(bitmapDecoder, gifDecoder, bitmapPool, DEFAULT_PARSER, DEFAULT_STREAM_FACTORY);
    }

    // Visible for testing.
    GifBitmapWrapperResourceDecoder(ResourceDecoder<ImageVideoWrapper, Bitmap> bitmapDecoder,
            ResourceDecoder<InputStream, GifDrawable> gifDecoder, BitmapPool bitmapPool, ImageTypeParser parser,
            BufferedStreamFactory streamFactory) {
        this.bitmapDecoder = bitmapDecoder;
        this.gifDecoder = gifDecoder;
        this.bitmapPool = bitmapPool;
        this.parser = parser;
        this.streamFactory = streamFactory;
    }

    @SuppressWarnings("resource")
    // @see ResourceDecoder.decode
    @Override
    public Resource<GifBitmapWrapper> decode(ImageVideoWrapper source, int width, int height) throws IOException {
        ByteArrayPool pool = ByteArrayPool.get();
        byte[] tempBytes = pool.getBytes();

        GifBitmapWrapper wrapper = null;
        try {
            // 调用了另外一个decode()方法的重载
            wrapper = decode(source, width, height, tempBytes);
        } finally {
            pool.releaseBytes(tempBytes);
        }
        // 又将GifBitmapWrapper封装到了一个GifBitmapWrapperResource对象当中，最终返回的是一个Resource<GifBitmapWrapper>对象
        // 这个GifBitmapWrapperResource和刚才的BitmapResource是相似的，它们都实现的Resource接口，都可以通过get()方法来获取封装起来的具体内容
        // 具体可以看GifBitmapWrapperResource代码
        // 经过这一层的封装之后，我们从网络上得到的图片就能够以Resource接口的形式返回，并且还能同时处理Bitmap图片和GIF图片这两种情况。
        return wrapper != null ? new GifBitmapWrapperResource(wrapper) : null;
    }

    private GifBitmapWrapper decode(ImageVideoWrapper source, int width, int height, byte[] bytes) throws IOException {
        final GifBitmapWrapper result;
        if (source.getStream() != null) {
            // 调用了decodeStream()方法，准备从服务器返回的流当中读取数据,进去
            result = decodeStream(source, width, height, bytes);
        } else {
            result = decodeBitmapWrapper(source, width, height);
        }
        return result;
    }

    /**
     * decodeStream()方法中会先从流中读取2个字节的数据，来判断这张图是GIF图还是普通的静图，
     * 如果是GIF图就调用decodeGifWrapper()方法来进行解码，
     * 如果是普通的静图就用调用decodeBitmapWrapper()方法来进行解码。
     * 这里我们只分析普通静图的实现流程，GIF图的实现有点过于复杂了，无法在本篇文章当中分析。
     */
    private GifBitmapWrapper decodeStream(ImageVideoWrapper source, int width, int height, byte[] bytes)
            throws IOException {
        InputStream bis = streamFactory.build(source.getStream(), bytes);
        bis.mark(MARK_LIMIT_BYTES);
        ImageHeaderParser.ImageType type = parser.parse(bis);
        bis.reset();

        GifBitmapWrapper result = null;
        if (type == ImageHeaderParser.ImageType.GIF) {
            result = decodeGifWrapper(bis, width, height);
        }
        // Decoding the gif may fail even if the type matches.
        if (result == null) {
            // We can only reset the buffered InputStream, so to start from the beginning of the stream, we need to
            // pass in a new source containing the buffered stream rather than the original stream.
            ImageVideoWrapper forBitmapDecoder = new ImageVideoWrapper(bis, source.getFileDescriptor());
            // 进去看看
            result = decodeBitmapWrapper(forBitmapDecoder, width, height);
        }
        return result;
    }

    private GifBitmapWrapper decodeGifWrapper(InputStream bis, int width, int height) throws IOException {
        GifBitmapWrapper result = null;
        Resource<GifDrawable> gifResource = gifDecoder.decode(bis, width, height);
        if (gifResource != null) {
            GifDrawable drawable = gifResource.get();
            // We can more efficiently hold Bitmaps in memory, so for static GIFs, try to return Bitmaps
            // instead. Returning a Bitmap incurs the cost of allocating the GifDrawable as well as the normal
            // Bitmap allocation, but since we can encode the Bitmap out as a JPEG, future decodes will be
            // efficient.
            if (drawable.getFrameCount() > 1) {
                result = new GifBitmapWrapper(null /*bitmapResource*/, gifResource);
            } else {
                Resource<Bitmap> bitmapResource = new BitmapResource(drawable.getFirstFrame(), bitmapPool);
                result = new GifBitmapWrapper(bitmapResource, null /*gifResource*/);
            }
        }
        return result;
    }

    /**
     * decodeBitmapWrapper()方法返回的是一个GifBitmapWrapper对象
     */
    private GifBitmapWrapper decodeBitmapWrapper(ImageVideoWrapper toDecode, int width, int height) throws IOException {
        GifBitmapWrapper result = null;

        // 这个bitmapDecoder是一个ImageVideoBitmapDecoder对象
        Resource<Bitmap> bitmapResource = bitmapDecoder.decode(toDecode, width, height);
        if (bitmapResource != null) {
            // 将Resource<Bitmap>封装到了一个GifBitmapWrapper对象当中
            // 这个GifBitmapWrapper顾名思义，就是既能封装GIF，又能封装Bitmap，从而保证了不管是什么类型的图片Glide都能从容应对
            // 看看GifBitmapWrapper
            result = new GifBitmapWrapper(bitmapResource, null);
        }

        return result;
    }

    @Override
    public String getId() {
        if (id == null) {
            id = gifDecoder.getId() + bitmapDecoder.getId();
        }
        return id;
    }

    // Visible for testing.
    static class BufferedStreamFactory {
        public InputStream build(InputStream is, byte[] buffer) {
            return new RecyclableBufferedInputStream(is, buffer);
        }
    }

    // Visible for testing.
    static class ImageTypeParser {
        public ImageHeaderParser.ImageType parse(InputStream is) throws IOException {
            return new ImageHeaderParser(is).getType();
        }
    }
}
