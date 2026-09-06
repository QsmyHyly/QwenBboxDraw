package com.dsh.qwenbbox.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * 可缩放的 ImageView：双击放大/还原，单指拖动，双指捏合缩放。
 * 用于在测试结果页点击放大查看「打框后的图片」。
 * 抽成独立类，便于多个 Activity 复用。
 */
public class ZoomableImageView extends ImageView {

    private final Matrix mMatrix = new Matrix();
    private ScaleGestureDetector mScale;
    private GestureDetector mDoubleTap;
    private float mLastX, mLastY;
    private boolean mScaling = false;
    private float mViewW, mViewH, mDrawW, mDrawH, mBaseScale = 1f;

    public ZoomableImageView(Context c) {
        super(c);
        init(c);
    }
    public ZoomableImageView(Context c, AttributeSet attrs) {
        super(c, attrs);
        init(c);
    }
    public ZoomableImageView(Context c, AttributeSet attrs, int defStyle) {
        super(c, attrs, defStyle);
        init(c);
    }

    private void init(Context c) {
        setScaleType(ScaleType.MATRIX);
        mScale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) { mScaling = true; return true; }
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                mMatrix.postScale(d.getScaleFactor(), d.getScaleFactor(),
                        d.getFocusX(), d.getFocusY());
                setImageMatrix(mMatrix);
                return true;
            }
            @Override
            public void onScaleEnd(ScaleGestureDetector d) { mScaling = false; }
        });
        mDoubleTap = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float[] v = new float[9];
                mMatrix.getValues(v);
                float cur = v[Matrix.MSCALE_X];
                if (cur > mBaseScale * 1.01f) {
                    // 还原
                    mMatrix.reset();
                    applyBaseMatrix();
                } else {
                    mMatrix.postScale(2.5f, 2.5f, e.getX(), e.getY());
                }
                clampTranslate();
                setImageMatrix(mMatrix);
                return true;
            }
        });
    }

    @Override
    protected boolean setFrame(int l, int t, int r, int b) {
        boolean changed = super.setFrame(l, t, r, b);
        mViewW = r - l;
        mViewH = b - t;
        mMatrix.reset();
        applyBaseMatrix();
        setImageMatrix(mMatrix);
        return changed;
    }

    @Override
    public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        if (bm != null) {
            mDrawW = bm.getWidth();
            mDrawH = bm.getHeight();
        }
        mMatrix.reset();
        applyBaseMatrix();
        setImageMatrix(mMatrix);
    }

    /** 计算「适配视图」的基础矩阵（等比缩放 + 居中）。 */
    private void applyBaseMatrix() {
        if (mDrawW <= 0 || mDrawH <= 0 || mViewW <= 0 || mViewH <= 0) return;
        float sx = mViewW / mDrawW;
        float sy = mViewH / mDrawH;
        float s = Math.min(sx, sy);
        mBaseScale = s;
        mMatrix.postScale(s, s);
        float tx = (mViewW - mDrawW * s) / 2f;
        float ty = (mViewH - mDrawH * s) / 2f;
        mMatrix.postTranslate(tx, ty);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        mScale.onTouchEvent(e);
        mDoubleTap.onTouchEvent(e);
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            mLastX = e.getX();
            mLastY = e.getY();
        } else if (e.getAction() == MotionEvent.ACTION_MOVE && !mScaling) {
            float dx = e.getX() - mLastX;
            float dy = e.getY() - mLastY;
            mMatrix.postTranslate(dx, dy);
            clampTranslate();
            setImageMatrix(mMatrix);
            mLastX = e.getX();
            mLastY = e.getY();
        }
        return true;
    }

    /** 拖动时限制不要把图片拖出可视区域太远。 */
    private void clampTranslate() {
        float[] v = new float[9];
        mMatrix.getValues(v);
        float scale = v[Matrix.MSCALE_X];
        float tx = v[Matrix.MTRANS_X];
        float ty = v[Matrix.MTRANS_Y];
        float drawW = mDrawW * scale;
        float drawH = mDrawH * scale;
        float maxX, minX, maxY, minY;
        if (drawW <= mViewW) {
            minX = (mViewW - drawW) / 2f;
            maxX = minX;
        } else {
            minX = mViewW - drawW;
            maxX = 0;
        }
        if (drawH <= mViewH) {
            minY = (mViewH - drawH) / 2f;
            maxY = minY;
        } else {
            minY = mViewH - drawH;
            maxY = 0;
        }
        tx = Math.max(minX, Math.min(maxX, tx));
        ty = Math.max(minY, Math.min(maxY, ty));
        mMatrix.postTranslate(tx - v[Matrix.MTRANS_X], ty - v[Matrix.MTRANS_Y]);
    }

    @SuppressWarnings("unused")
    private PointF mid(MotionEvent e) {
        return new PointF((e.getX(0) + e.getX(1)) / 2, (e.getY(0) + e.getY(1)) / 2);
    }
}
