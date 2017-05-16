package com.android.explore;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.ImageView;

import com.bumptech.glide.Glide;

public class MainActivity extends AppCompatActivity {

    ImageView imageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        imageView = (ImageView) findViewById(R.id.image_view);
    }

    public void loadImage(View view) {
        String url = "http://cn.bing.com/az/hprichbg/rb/Dongdaemun_ZH-CN10736487148_1920x1080.jpg";
        String gifUrl = "http://p1.pstatp.com/large/166200019850062839d3";
        //先with()，再load()，最后into()
        Glide.with(this).load(url)
                //只允许加载静态图片
//                .asBitmap()
//                .asGif()
//                .placeholder(R.mipmap.ic_launcher)
//                .error(R.mipmap.ic_launcher)
//                //禁用掉Glide的缓存功能
//                .diskCacheStrategy(DiskCacheStrategy.NONE)
//                //固定的大小
//                .override(100, 100)
                .into(imageView);
    }

}
