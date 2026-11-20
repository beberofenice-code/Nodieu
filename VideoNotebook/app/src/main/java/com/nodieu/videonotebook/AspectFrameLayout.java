package com.nodieu.videonotebook;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** A FrameLayout whose height follows its width (portrait poster shape). */
public class AspectFrameLayout extends FrameLayout {
    /** height = width * ratio. 1.5 = 2:3 poster. */
    private float ratio = 1.5f;

    public AspectFrameLayout(Context context) { super(context); }
    public AspectFrameLayout(Context context, AttributeSet attrs) { super(context, attrs); }
    public AspectFrameLayout(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); }

    public void setRatio(float r) {
        if (r != ratio) {
            ratio = r;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = View.MeasureSpec.getSize(widthMeasureSpec);
        int h = Math.round(w * ratio);
        super.onMeasure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
    }
}
