package com.bumptech.glide.request.target;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import com.bumptech.glide.load.resource.drawable.GlideDrawable;

/**
 * A factory responsible for producing the correct type of {@link com.bumptech.glide.request.target.Target} for a given
 * {@link android.view.View} subclass.
 */
public class ImageViewTargetFactory {

    /**
     * 根据传入的class参数来构建不同的Target对象
     * 这个class参数其实基本上只有两种情况，如果你在使用Glide加载图片的时候调用了asBitmap()方法，
     * 那么这里就会构建出BitmapImageViewTarget对象，否则的话构建的都是GlideDrawableImageViewTarget对象
     * 至于代码中的DrawableImageViewTarget对象，这个通常都是用不到的
     */
    @SuppressWarnings("unchecked")
    public <Z> Target<Z> buildTarget(ImageView view, Class<Z> clazz) {
        if (GlideDrawable.class.isAssignableFrom(clazz)) {
            return (Target<Z>) new GlideDrawableImageViewTarget(view);
        } else if (Bitmap.class.equals(clazz)) {
            return (Target<Z>) new BitmapImageViewTarget(view);
        } else if (Drawable.class.isAssignableFrom(clazz)) {
            return (Target<Z>) new DrawableImageViewTarget(view);
        } else {
            throw new IllegalArgumentException("Unhandled class: " + clazz
                    + ", try .as*(Class).transcode(ResourceTranscoder)");
        }
    }
}
