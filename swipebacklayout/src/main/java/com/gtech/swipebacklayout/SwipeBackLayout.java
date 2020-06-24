package com.gtech.swipebacklayout;

import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.FloatRange;
import androidx.annotation.IntDef;
import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.customview.widget.ViewDragHelper;

import com.gtech.swipebacklayout.tools.Util;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class SwipeBackLayout extends ViewGroup {
    private static final double DRAG_HORIZONTALLY_MAX_ANGLE = Math.PI / 6;
    private static final double DRAG_VERTICALLY_MAX_ANGLE = Math.PI / 4;

    public static final int FROM_LEFT = 1 << 0;
    public static final int FROM_RIGHT = 1 << 1;
    public static final int FROM_TOP = 1 << 2;
    public static final int FROM_BOTTOM = 1 << 3;

    @IntDef({FROM_LEFT, FROM_TOP, FROM_RIGHT, FROM_BOTTOM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface DirectionMode {
    }

    private int mDirectionMode = FROM_LEFT;

    private final ViewDragHelper mDragHelper;
    private View mDragContentView;
    private View innerScrollView;

    private int width, height;

    private int mTouchSlop;
    private float swipeBackFactor = 0.5f;
    private float swipeBackFraction;
    private int maskAlpha = 125;
    private boolean isSwipeFromEdge = false;
    private float downX, downY;

    private int leftOffset = 0;
    private int topOffset = 0;
    private float autoFinishedVelocityLimit = 2000f;
    private boolean mIsSwipingEnabled = true;
    private boolean mShouldCancelSwiping;
    private boolean mIsSwiping;

    private double dragHorizontallyMaxAngle = DRAG_HORIZONTALLY_MAX_ANGLE;
    private double dragVerticallyMaxAngle = DRAG_VERTICALLY_MAX_ANGLE;
    private int touchedEdge = ViewDragHelper.INVALID_POINTER;

    private float mPrevMoveX = -1;
    private boolean mCheckInnerScrollView = false;

    public SwipeBackLayout(@NonNull Context context) {
        this(context, null);
    }

    public SwipeBackLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SwipeBackLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        mDragHelper = ViewDragHelper.create(this, 1f, new DragHelperCallback());
        mDragHelper.setEdgeTrackingEnabled(mDirectionMode);
        mTouchSlop = mDragHelper.getTouchSlop();
        setSwipeBackListener(defaultSwipeBackListener);

        init(context, attrs);
    }

    private void init(@NonNull Context context, @Nullable AttributeSet attrs) {
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SwipeBackLayout);
        setDirectionMode(a.getInt(R.styleable.SwipeBackLayout_directionMode, mDirectionMode));
        setSwipeBackFactor(a.getFloat(R.styleable.SwipeBackLayout_swipeBackFactor, swipeBackFactor));
        setMaskAlpha(a.getInteger(R.styleable.SwipeBackLayout_maskAlpha, maskAlpha));
        isSwipeFromEdge = a.getBoolean(R.styleable.SwipeBackLayout_isSwipeFromEdge, isSwipeFromEdge);
        mCheckInnerScrollView = a.getBoolean(R.styleable.SwipeBackLayout_checkInnerScrollView, mCheckInnerScrollView);
        a.recycle();
    }

    public void attachToActivity(Activity activity) {
        ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
        ViewGroup decorChild = (ViewGroup) decorView.getChildAt(0);
        decorChild.setBackgroundColor(Color.TRANSPARENT);
        decorView.removeView(decorChild);
        addView(decorChild);
        decorView.addView(this);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int childCount = getChildCount();
        if (childCount > 1) {
            throw new IllegalStateException("SwipeBackLayout must contains only one direct child.");
        }
        int defaultMeasuredWidth = 0;
        int defaultMeasuredHeight = 0;
        int measuredWidth;
        int measuredHeight;
        if (childCount > 0) {
            measureChildren(widthMeasureSpec, heightMeasureSpec);
            mDragContentView = getChildAt(0);
            defaultMeasuredWidth = mDragContentView.getMeasuredWidth();
            defaultMeasuredHeight = mDragContentView.getMeasuredHeight();
        }
        measuredWidth = View.resolveSize(defaultMeasuredWidth, widthMeasureSpec) + getPaddingLeft() + getPaddingRight();
        measuredHeight = View.resolveSize(defaultMeasuredHeight, heightMeasureSpec) + getPaddingTop() + getPaddingBottom();

        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (getChildCount() == 0) return;

        int left = getPaddingLeft() + leftOffset;
        int top = getPaddingTop() + topOffset;
        int right = left + mDragContentView.getMeasuredWidth();
        int bottom = top + mDragContentView.getMeasuredHeight();
        mDragContentView.layout(left, top, right, bottom);

        if (changed) {
            width = getWidth();
            height = getHeight();
        }
        innerScrollView = Util.findAllScrollViews(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawARGB(maskAlpha - (int) (maskAlpha * swipeBackFraction), 0, 0, 0);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!mIsSwipingEnabled) {
            return false;
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                resetTouch();
                downX = ev.getRawX();
                downY = ev.getRawY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (mShouldCancelSwiping) {
                    return false;
                }

                float distanceX = Math.abs(ev.getRawX() - downX);
                float distanceY = Math.abs(ev.getRawY() - downY);
                if (mDirectionMode == FROM_LEFT || mDirectionMode == FROM_RIGHT) {
                    if (mPrevMoveX > 0) {
                        if (distanceX > mTouchSlop) {
                            boolean cancel = (mDirectionMode == FROM_LEFT && ev.getRawX() < downX)
                                    || (mDirectionMode == FROM_RIGHT && ev.getRawX() > downX);
                            if (cancel || distanceY > distanceX * Math.tan(dragHorizontallyMaxAngle)) {
                                mShouldCancelSwiping = true;
                                return false;
                            } else {
                                mIsSwiping = true;
                                boolean checkInnerScrollView = mCheckInnerScrollView &&
                                        innerScrollView != null &&
                                        Util.contains(innerScrollView, downX, downY);
                                return !checkInnerScrollView || super.onInterceptTouchEvent(ev);
                            }
                        } else {
                            return false;
                        }
                    }
                    mPrevMoveX = ev.getX();
                } else if (mDirectionMode == FROM_TOP || mDirectionMode == FROM_BOTTOM) {
                    if (distanceY > mTouchSlop) {
                        boolean cancel = (mDirectionMode == FROM_TOP && ev.getRawY() < downY)
                                || (mDirectionMode == FROM_BOTTOM && ev.getRawY() > downY);
                        if (cancel || distanceX > distanceY * Math.tan(dragVerticallyMaxAngle)) {
                            mShouldCancelSwiping = true;
                            return false;
                        } else {
                            mIsSwiping = true;
                            return true;
                        }
                    } else {
                        return false;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                resetTouch();
                break;
        }
        boolean handled = mDragHelper.shouldInterceptTouchEvent(ev);
        return handled | super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (mShouldCancelSwiping) {
                    return false;
                } else if (mIsSwiping) {
                    mDragHelper.processTouchEvent(event);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                resetTouch();
                break;
        }

        mDragHelper.processTouchEvent(event);
        return true;
    }

    private void resetTouch() {
        mShouldCancelSwiping = false;
        mIsSwiping = false;
        mPrevMoveX = -1;
    }

    @Override
    public void computeScroll() {
        if (mDragHelper.continueSettling(true)) {
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }

    public void smoothScrollToX(int finalLeft) {
        if (mDragHelper.settleCapturedViewAt(finalLeft, getPaddingTop())) {
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }

    public void smoothScrollToY(int finalTop) {
        if (mDragHelper.settleCapturedViewAt(getPaddingLeft(), finalTop)) {
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }

    private class DragHelperCallback extends ViewDragHelper.Callback {

        @Override
        public boolean tryCaptureView(View child, int pointerId) {
            return child == mDragContentView;
        }

        @Override
        public int clampViewPositionHorizontal(View child, int left, int dx) {
            leftOffset = getPaddingLeft();
            if (isSwipeEnabled()) {
                if (mDirectionMode == FROM_LEFT && !Util.canViewScrollRight(innerScrollView, downX, downY, false)) {
                    leftOffset = Math.min(Math.max(left, getPaddingLeft()), width);
                } else if (mDirectionMode == FROM_RIGHT && !Util.canViewScrollLeft(innerScrollView, downX, downY, false)) {
                    leftOffset = Math.min(Math.max(left, -width), getPaddingRight());
                }
            }
            return leftOffset;
        }

        @Override
        public int clampViewPositionVertical(View child, int top, int dy) {
            topOffset = getPaddingTop();
            if (isSwipeEnabled()) {
                if (mDirectionMode == FROM_TOP && !Util.canViewScrollUp(innerScrollView, downX, downY, false)) {
                    topOffset = Math.min(Math.max(top, getPaddingTop()), height);
                } else if (mDirectionMode == FROM_BOTTOM && !Util.canViewScrollDown(innerScrollView, downX, downY, false)) {
                    topOffset = Math.min(Math.max(top, -height), getPaddingBottom());
                }
            }
            return topOffset;
        }

        @Override
        public void onViewPositionChanged(View changedView, int left, int top, int dx, int dy) {
            super.onViewPositionChanged(changedView, left, top, dx, dy);
            left = Math.abs(left);
            top = Math.abs(top);
            switch (mDirectionMode) {
                case FROM_LEFT:
                case FROM_RIGHT:
                    swipeBackFraction = 1.0f * left / width;
                    break;
                case FROM_TOP:
                case FROM_BOTTOM:
                    swipeBackFraction = 1.0f * top / height;
                    break;
            }
            if (mSwipeBackListener != null) {
                mSwipeBackListener.onViewPositionChanged(mDragContentView, swipeBackFraction, swipeBackFactor);
            }
        }

        @Override
        public void onViewReleased(View releasedChild, float xvel, float yvel) {
            super.onViewReleased(releasedChild, xvel, yvel);
            leftOffset = topOffset = 0;
            if (!isSwipeEnabled()) {
                touchedEdge = ViewDragHelper.INVALID_POINTER;
                return;
            }
            touchedEdge = ViewDragHelper.INVALID_POINTER;

            boolean isBackToEnd = backJudgeBySpeed(xvel, yvel) || swipeBackFraction >= swipeBackFactor;
            if (isBackToEnd) {
                switch (mDirectionMode) {
                    case FROM_LEFT:
                        smoothScrollToX(width);
                        break;
                    case FROM_TOP:
                        smoothScrollToY(height);
                        break;
                    case FROM_RIGHT:
                        smoothScrollToX(-width);
                        break;
                    case FROM_BOTTOM:
                        smoothScrollToY(-height);
                        break;
                }
            } else {
                switch (mDirectionMode) {
                    case FROM_LEFT:
                    case FROM_RIGHT:
                        smoothScrollToX(getPaddingLeft());
                        break;
                    case FROM_BOTTOM:
                    case FROM_TOP:
                        smoothScrollToY(getPaddingTop());
                        break;
                }
            }
        }

        @Override
        public void onViewDragStateChanged(int state) {
            super.onViewDragStateChanged(state);
            if (state == ViewDragHelper.STATE_IDLE) {
                if (mSwipeBackListener != null) {
                    if (swipeBackFraction == 0) {
                        mSwipeBackListener.onViewSwipeFinished(mDragContentView, false);
                    } else if (swipeBackFraction == 1) {
                        mSwipeBackListener.onViewSwipeFinished(mDragContentView, true);
                    }
                }
            }
        }

        @Override
        public int getViewHorizontalDragRange(View child) {
            return width;
        }

        @Override
        public int getViewVerticalDragRange(View child) {
            return height;
        }

        @Override
        public void onEdgeTouched(int edgeFlags, int pointerId) {
            super.onEdgeTouched(edgeFlags, pointerId);
            touchedEdge = edgeFlags;
        }
    }

    public void finish() {
        ((Activity) getContext()).finish();
    }

    private boolean isSwipeEnabled() {
        if (isSwipeFromEdge) {
            switch (mDirectionMode) {
                case FROM_LEFT:
                    return touchedEdge == ViewDragHelper.EDGE_LEFT;
                case FROM_TOP:
                    return touchedEdge == ViewDragHelper.EDGE_TOP;
                case FROM_RIGHT:
                    return touchedEdge == ViewDragHelper.EDGE_RIGHT;
                case FROM_BOTTOM:
                    return touchedEdge == ViewDragHelper.EDGE_BOTTOM;
            }
        }
        return true;
    }

    private boolean backJudgeBySpeed(float xvel, float yvel) {
        switch (mDirectionMode) {
            case FROM_LEFT:
                return xvel > autoFinishedVelocityLimit;
            case FROM_TOP:
                return yvel > autoFinishedVelocityLimit;
            case FROM_RIGHT:
                return xvel < -autoFinishedVelocityLimit;
            case FROM_BOTTOM:
                return yvel < -autoFinishedVelocityLimit;
        }
        return false;
    }

    public void setSwipeBackFactor(@FloatRange(from = 0.0f, to = 1.0f) float swipeBackFactor) {
        if (swipeBackFactor > 1) {
            swipeBackFactor = 1;
        } else if (swipeBackFactor < 0) {
            swipeBackFactor = 0;
        }
        this.swipeBackFactor = swipeBackFactor;
    }

    public float getSwipeBackFactor() {
        return swipeBackFactor;
    }

    public void setMaskAlpha(@IntRange(from = 0, to = 255) int maskAlpha) {
        if (maskAlpha > 255) {
            maskAlpha = 255;
        } else if (maskAlpha < 0) {
            maskAlpha = 0;
        }
        this.maskAlpha = maskAlpha;
    }

    public int getMaskAlpha() {
        return maskAlpha;
    }

    public void setDirectionMode(@DirectionMode int direction) {
        mDirectionMode = direction;
        mDragHelper.setEdgeTrackingEnabled(direction);
    }

    public int getDirectionMode() {
        return mDirectionMode;
    }

    public float getAutoFinishedVelocityLimit() {
        return autoFinishedVelocityLimit;
    }

    public void setAutoFinishedVelocityLimit(float autoFinishedVelocityLimit) {
        this.autoFinishedVelocityLimit = autoFinishedVelocityLimit;
    }

    public boolean isSwipeFromEdge() {
        return isSwipeFromEdge;
    }

    public void setSwipeFromEdge(boolean isSwipeFromEdge) {
        this.isSwipeFromEdge = isSwipeFromEdge;
    }

    private OnSwipeBackListener mSwipeBackListener;

    private OnSwipeBackListener defaultSwipeBackListener = new OnSwipeBackListener() {
        @Override
        public void onViewPositionChanged(View mView, float swipeBackFraction, float swipeBackFactor) {
            invalidate();
        }

        @Override
        public void onViewSwipeFinished(View mView, boolean isEnd) {
            if (isEnd) {
                finish();
            }
        }
    };

    public void setSwipeBackListener(OnSwipeBackListener mSwipeBackListener) {
        this.mSwipeBackListener = mSwipeBackListener;
    }

    public interface OnSwipeBackListener {

        void onViewPositionChanged(View mView, float swipeBackFraction, float swipeBackFactor);

        void onViewSwipeFinished(View mView, boolean isEnd);

    }

    public void setDragHorizontallyMaxAngle(double dragHorizontallyMaxAngle) {
        this.dragHorizontallyMaxAngle = dragHorizontallyMaxAngle;
    }

    public void setDragVerticallyMaxAngle(double dragVerticallyMaxAngle) {
        this.dragVerticallyMaxAngle = dragVerticallyMaxAngle;
    }

    public void setSwipingEnabled(boolean isEnabled) {
        mIsSwipingEnabled = isEnabled;
    }

    public void setTouchSlop(int touchSlop) {
        mTouchSlop = touchSlop;
    }
}
