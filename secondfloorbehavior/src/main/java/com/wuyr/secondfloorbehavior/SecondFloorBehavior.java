package com.wuyr.secondfloorbehavior;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.TypedArray;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * @author wuyr
 * @github https://github.com/wuyr/SecondFloorBehavior
 * @since 2019-11-25 下午7:59
 */
@SuppressWarnings({"unused", "WeakerAccess", "BooleanMethodIsAlwaysInverted"})
public class SecondFloorBehavior extends CoordinatorLayout.Behavior<View> {

    /**
     * 普通状态
     */
    public static final int STATE_NORMAL = 0;

    /**
     * 正在进入二楼
     */
    public static final int STATE_OPENING = 1;

    /**
     * 在二楼
     */
    public static final int STATE_OPENED = 2;

    /**
     * 正在离开二楼
     */
    public static final int STATE_CLOSING = 3;

    private int mState = STATE_NORMAL;

    /**
     * 开始拦截下拉的滑动距离
     * （即：列表滑动到顶后，往下拉多长距离可以开始触发二楼的下拉？）
     */
    private float mStartInterceptDistance;

    /**
     * 能够进入二楼的滑动距离(从触发上面的二楼下拉后开始计算)
     * （即：拦截下拉后，至少还要继续往下滑动多长距离才能够触发进入二楼？）
     */
    private float mMinTriggerDistance;

    /**
     * 触发下拉后的滑动距离衰减率
     */
    private float mDampingRatio;

    /**
     * 回退的动画时长
     */
    private long mRollbackDuration;

    /**
     * 进入二楼的动画时长
     */
    private long mEnterDuration;

    /**
     * 退出二楼的动画时长
     */
    private long mExitDuration;

    private int mActivePointerId = MotionEvent.INVALID_POINTER_ID;
    private int mLastDispatchPointerId = MotionEvent.INVALID_POINTER_ID;

    private float mLastY;
    private float mLastDispatchY;
    private float mLastDispatchX;

    private float mPullDownOffset;
    private float mLastMoveOffset;

    private boolean mDragging;
    private boolean mPullDownStarted;
    private boolean mNeedCheckInsertEvent;

    //寄主
    private ViewGroup mParent;

    private OnBeforeEnterSecondFloorListener mOnBeforeEnterSecondFloorListener;
    private OnEnterSecondFloorListener mOnEnterSecondFloorListener;
    private OnExitSecondFloorListener mOnExitSecondFloorListener;
    private OnStateChangeListener mOnStateChangeListener;

    public SecondFloorBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CoordinatorLayout_Layout);
        initAttributes(a);
        initListener(context, a);
        a.recycle();
    }

    private void initListener(final Context context, TypedArray a) {
        final String enterMethod = a.getString(R.styleable.CoordinatorLayout_Layout_layout_onEnterSecondFloor);
        final String exitMethod = a.getString(R.styleable.CoordinatorLayout_Layout_layout_onExitSecondFloor);
        if (!TextUtils.isEmpty(enterMethod)) {
            setOnEnterSecondFloorListener(new OnEnterSecondFloorListener() {

                private DeclaredHelper helper = new DeclaredHelper(context, enterMethod, "app:layout_onEnterSecondFloor");

                @Override
                public void onEnterSecondFloor() {
                    helper.invoke();
                }
            });
        }
        if (!TextUtils.isEmpty(exitMethod)) {
            setOnExitSecondFloorListener(new OnExitSecondFloorListener() {

                private DeclaredHelper helper = new DeclaredHelper(context, exitMethod, "app:layout_onExitSecondFloor");

                @Override
                public void onExitSecondFloor() {
                    helper.invoke();
                }
            });
        }
    }

    private void initAttributes(TypedArray a) {
        mStartInterceptDistance = a.getDimensionPixelSize(R.styleable.CoordinatorLayout_Layout_layout_startInterceptDistance, 0);
        mMinTriggerDistance = a.getDimensionPixelSize(R.styleable.CoordinatorLayout_Layout_layout_minTriggerOffset, 0);
        mDampingRatio = a.getFloat(R.styleable.CoordinatorLayout_Layout_layout_dampingRatio, 0);
        if (mDampingRatio > 1) {
            mDampingRatio = 1;
        } else if (mDampingRatio < 0) {
            mDampingRatio = 0;
        }
        mDampingRatio = 1F - mDampingRatio;
        mRollbackDuration = a.getInt(R.styleable.CoordinatorLayout_Layout_layout_rollbackDuration, 200);
        mEnterDuration = a.getInt(R.styleable.CoordinatorLayout_Layout_layout_enterDuration, 500);
        mExitDuration = a.getInt(R.styleable.CoordinatorLayout_Layout_layout_exitDuration, 400);
    }

    /**
     * 进入二楼
     */
    public void enterSecondFloor() {
        if (!isAnimationPlaying() && !isOnOrGoingToSecondFloor()) {
            gotoSecondFloor(null, false);
        }
    }

    /**
     * 离开二楼
     */
    public void leaveSecondFloor() {
        if (isAnimationPlaying() || !isOnOrGoingToSecondFloor()) {
            return;
        }
        onStateChange(STATE_CLOSING);

        getHeaderView().animate().setDuration(mExitDuration).translationY(0).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                onStateChange(STATE_NORMAL);
            }
        }).start();
        getSecondFloorView().animate().setDuration(mExitDuration).translationY(0).start();
        getFirstFloorView().animate().setDuration(mExitDuration).translationY(0).start();

        if (mOnExitSecondFloorListener != null) {
            mOnExitSecondFloorListener.onExitSecondFloor();
        }
    }

    @Override
    public boolean onInterceptTouchEvent(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull MotionEvent ev) {
        //只要还没有进入二楼，就要拦截事件
        return mState != STATE_OPENED;
    }

    @Override
    public boolean onTouchEvent(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull MotionEvent ev) {
        if (isAnimationPlaying()) return true;

        //还没有开始拖动就收到了DOWN之外的事件，不作处理
        if (!mDragging && ev.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return true;
        }

        boolean handled = false;

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                handled = handleActionPointerDown(ev);
                break;
            case MotionEvent.ACTION_DOWN:
                handleActionDown(ev);
                break;
            case MotionEvent.ACTION_MOVE:
                handled = handleActionMove(ev);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                handled = handleActionPointerUp(ev);
                break;
            case MotionEvent.ACTION_UP:
                handled = handleActionUp(ev);
                break;
            case MotionEvent.ACTION_CANCEL:
                if (!mDragging) {
                    handled = true;
                }
                break;
        }
        //判断是否要彻底拦截事件，有以下2种情况：
        //1. 上面处理滑动的逻辑需要拦截；
        //2. 收到了来自requestDisallowInterceptTouchEvent方法发出的ACTION_CANCEL事件
        if (handled || ev.getAction() == MotionEvent.ACTION_CANCEL && Thread.currentThread().getStackTrace()[4].getMethodName().equals("requestDisallowInterceptTouchEvent")) {
            return true;
        }
        return dispatchTouchEvent(ev);
    }

    private boolean dispatchTouchEvent(@NonNull MotionEvent ev) {
        if (mNeedCheckInsertEvent) {
            mNeedCheckInsertEvent = false;
            MotionEvent insertEvent = null;
            //防止距离足够触发二楼时，往回拉时换了手指，有以下几种情况:
            //1. 手指抬起时，原来的指针id无效；
            //2. 手指移动时，原来的指针id无效；
            //3. 手指移动时，原来的指针id无效，但当前指针id有效；
            //4. 手指移动时，由最开始的多指变为单指；
            boolean pointerIdInvalid = mLastDispatchPointerId == MotionEvent.INVALID_POINTER_ID || ev.findPointerIndex(mLastDispatchPointerId) == -1;
            if (ev.getAction() == MotionEvent.ACTION_UP && pointerIdInvalid ||
                    ev.getAction() == MotionEvent.ACTION_MOVE && pointerIdInvalid || mLastDispatchPointerId == mActivePointerId && ev.getPointerCount() == 1) {
                insertEvent = MotionEvent.obtain(ev);
            }
            //手动滑回来的时候找不到之前的手指id，所以现在要模拟新手指按下和旧手指抬起
            if (insertEvent != null) {
                insertEvent.setAction(MotionEvent.ACTION_POINTER_DOWN);
                getFirstFloorView().dispatchTouchEvent(insertEvent);
                insertEvent.recycle();
            }
        }
        updateDispatchLocation(ev);
        return getFirstFloorView().dispatchTouchEvent(ev);
    }

    private void updateDispatchLocation(@NonNull MotionEvent ev) {
        int pi = findValidActionIndex(ev, mLastDispatchPointerId);
        mLastDispatchY = ev.getY(pi);
        mLastDispatchX = ev.getX(pi);
    }

    private boolean handleActionUp(@NonNull MotionEvent ev) {
        boolean handled = false;
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        mLastY = 0;
        if (mDragging) {
            if (mPullDownStarted && mPullDownOffset < -mStartInterceptDistance) {
                //手指抬起的时候，如果滑动超过了指定距离，则进入二楼，否则回退
                if (getHeaderView().getTranslationY() >= mMinTriggerDistance) {
                    enterSecondFloor(ev);
                    handled = true;
                } else {
                    rollback();
                }
            }
            mPullDownOffset = 0;
            mLastMoveOffset = 0;
            mPullDownStarted = false;
            mDragging = false;
        } else {
            handled = true;
        }
        return handled;
    }

    private boolean handleActionPointerUp(@NonNull MotionEvent ev) {
        onSecondaryPointerUp(ev);
        //已经到了拦截的距离，就继续拦截
        return mPullDownStarted && mPullDownOffset < -mStartInterceptDistance;
    }

    private boolean handleActionMove(@NonNull MotionEvent ev) {
        boolean handled = false;
        if (mPullDownStarted) {
            if (mActivePointerId == MotionEvent.INVALID_POINTER_ID) {
                mActivePointerId = ev.getPointerId(ev.getActionIndex());
            }
            int actionIndex = findValidActionIndex(ev, mActivePointerId);
            float offset = ev.getY(actionIndex) - mLastY;
            mPullDownOffset -= offset;
            if (mPullDownOffset > 0) {
                //回退到下拉前
                mPullDownStarted = false;
                mPullDownOffset = 0;
                mLastMoveOffset = 0;
            } else if (mPullDownOffset < -mStartInterceptDistance) {
                //计算出溢出的偏移量
                float overflowOffset = -mStartInterceptDistance - mPullDownOffset;
                if (mPullDownOffset + offset >= -mStartInterceptDistance) {
                    //初次到达触发点，标记等下要检查是否需要插入事件
                    mNeedCheckInsertEvent = true;
                    //修正滑动溢出
                    fixMoveOverflow(ev, overflowOffset);
                }
                handled = true;

                float moveOffset = overflowOffset - mLastMoveOffset;
                mLastMoveOffset = overflowOffset;
                moveOffset *= mDampingRatio;

                offsetChildren(moveOffset);
            } else if (mPullDownOffset + offset < -mStartInterceptDistance) {
                //初次回到触发点
                if (ev.getPointerCount() == 1) {
                    //计算出溢出的偏移量
                    float overflowOffset = -mStartInterceptDistance - mPullDownOffset;
                    mPullDownOffset += overflowOffset;
                }

                translationChildrenY(0);
                mLastMoveOffset = 0;
            }
        }
        updateLastY(ev);
        return handled;
    }

    private void offsetChildren(float offset) {
        View headerView = getHeaderView();

        //偏移的距离还没有超过HeaderView的高度
        if (headerView.getTranslationY() + offset < headerView.getHeight()) {
            translationChildrenYBy(offset);

            //防止过度往下拖动后，向上滑动时一楼底部脱离屏幕底部
            if (headerView.getTranslationY() <= 0) {
                mPullDownOffset -= headerView.getTranslationY() / mDampingRatio;
                translationChildrenY(0);
            }
        } else {
            //如果滑动距离已经超出了HeaderView的高度的话，就要固定在这个高度
            float topOverflow = headerView.getTranslationY() + offset - headerView.getHeight();

            //不增加偏移量
            mLastMoveOffset -= topOverflow;
            mPullDownOffset += topOverflow;

            //修正偏移距离
            float maxTranslationY = headerView.getHeight();
            translationChildrenY(maxTranslationY);
        }
    }

    private void translationChildrenY(float translation) {
        View headerView = getHeaderView();
        View secondFloorView = getSecondFloorView();
        View firstFloorView = getFirstFloorView();

        firstFloorView.setTranslationY(translation);
        headerView.setTranslationY(translation);
        secondFloorView.setTranslationY(translation);
    }

    private void translationChildrenYBy(float translation) {
        translationChildrenY(getHeaderView().getTranslationY() + translation);
    }

    private void fixMoveOverflow(@NonNull MotionEvent ev, float overflowOffset) {
        //因为超出了指定的触发点，所以要退回去，也就是减去超出的偏移量了
        MotionEvent appendEvent = reassignEventId(ev, mLastDispatchPointerId, ev.getAction(), ev.getRawX(), ev.getRawY() - overflowOffset);

        int pi = findValidActionIndex(ev, mLastDispatchPointerId);
        appendEvent.offsetLocation(ev.getX(pi) - ev.getRawX(), ev.getY(pi) - ev.getRawY());

        mLastDispatchY = ev.getY(pi);
        mLastDispatchX = ev.getX(pi);
        getFirstFloorView().dispatchTouchEvent(appendEvent);
        appendEvent.recycle();
    }

    private int findValidActionIndex(@NonNull MotionEvent ev, int id) {
        int actionIndex = ev.findPointerIndex(id);
        return actionIndex == -1 ? 0 : actionIndex;
    }

    private void handleActionDown(@NonNull MotionEvent ev) {
        mActivePointerId = ev.getPointerId(0);
        //有手指按下的时候，如果还没触发二楼的下拉，就更新id
        if (mPullDownOffset >= -mStartInterceptDistance) {
            mLastDispatchPointerId = mActivePointerId;
        }
        mDragging = true;
        mPullDownOffset = 0;
        mLastMoveOffset = 0;
        updateLastY(ev);
    }

    private boolean handleActionPointerDown(@NonNull MotionEvent ev) {
        mActivePointerId = ev.getPointerId(ev.getActionIndex());
        //有手指按下的时候，如果还没触发二楼的下拉，就更新id
        if (mPullDownOffset >= -mStartInterceptDistance) {
            mLastDispatchPointerId = mActivePointerId;
        }
        updateLastY(ev);
        return mPullDownStarted && mPullDownOffset < -mStartInterceptDistance;
    }

    private void updateLastY(MotionEvent ev) {
        mLastY = mActivePointerId == MotionEvent.INVALID_POINTER_ID ? 0 : ev.getY(findValidActionIndex(ev, mActivePointerId));
    }

    private boolean isAnimationPlaying() {
        return mState == STATE_OPENING || mState == STATE_CLOSING;
    }

    private boolean isOnOrGoingToSecondFloor() {
        return mState == STATE_OPENED || mState == STATE_OPENING;
    }

    private void enterSecondFloor(MotionEvent ev) {
        if (!isAnimationPlaying()) {
            gotoSecondFloor(ev, true);
        }
    }

    private void gotoSecondFloor(final MotionEvent ev, final boolean fakeScroll) {
        if (mOnBeforeEnterSecondFloorListener == null || mOnBeforeEnterSecondFloorListener.onBeforeEnterSecondFloor()) {
            onStateChange(STATE_OPENING);

            final View headerView = getHeaderView();
            final View secondFloorView = getSecondFloorView();
            final View firstFloorView = getFirstFloorView();

            ValueAnimator animator = smoothTranslationBy(headerView, firstFloorView.getHeight(), mEnterDuration);
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    onStateChange(STATE_OPENED);
                    if (fakeScroll) {
                        fakeScroll(firstFloorView, -mStartInterceptDistance, ev);
                    }
                }
            });
            animator.start();
            smoothTranslationBy(secondFloorView, firstFloorView.getHeight() + headerView.getHeight(), mEnterDuration).start();
            smoothTranslationBy(firstFloorView, firstFloorView.getHeight(), mEnterDuration).start();

            if (mOnEnterSecondFloorListener != null) {
                mOnEnterSecondFloorListener.onEnterSecondFloor();
            }
        } else {
            rollback();
            if (ev != null) {
                dispatchTouchEvent(ev);
            }
        }
    }

    private ValueAnimator smoothTranslationBy(final View target, float translation, long duration) {
        ValueAnimator animator = ValueAnimator.ofFloat(target.getTranslationY(), translation).setDuration(duration);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                target.setTranslationY((Float) animation.getAnimatedValue());
            }
        });
        return animator;
    }

    private void rollback() {
        if (isAnimationPlaying()) {
            return;
        }

        ValueAnimator animator = ValueAnimator.ofFloat(getHeaderView().getTranslationY(), 0);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                translationChildrenY((float) animation.getAnimatedValue());
            }
        });
        animator.setDuration(mRollbackDuration);
        animator.start();
    }

    private void onSecondaryPointerUp(MotionEvent ev) {
        int pointerIndex = ev.getActionIndex();
        int pointerId = ev.getPointerId(pointerIndex);
        //如果抬起的那根手指，刚好是当前活跃的手指，那么
        if (pointerId == mActivePointerId) {
            //另选一根手指，并把它标记为活跃
            int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mActivePointerId = ev.getPointerId(newPointerIndex);
            //还没有触发二楼下拉，就更新id
            if (mPullDownOffset >= -mStartInterceptDistance) {
                mLastDispatchPointerId = mActivePointerId;
            }
            mLastY = ev.getY(newPointerIndex);
        }
    }

    @Override
    public boolean layoutDependsOn(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        //索引分别对应：0: Header、1: 二楼、2: 一楼
        //要监听的是一楼的各种状态变化
        return dependency == parent.getChildAt(2);
    }

    @Override
    public boolean onStartNestedScroll(@NonNull CoordinatorLayout coordinatorLayout, @NonNull View child, @NonNull View directTargetChild, @NonNull View target, int axes, int type) {
        //只需要监听一楼的滚动
        return directTargetChild == coordinatorLayout.getChildAt(2);
    }

    @Override
    public void onNestedScroll(@NonNull CoordinatorLayout coordinatorLayout, @NonNull View child, @NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int type) {
        if (dyUnconsumed < 0 && mDragging && !mPullDownStarted && mPullDownOffset >= 0) {
            mPullDownStarted = true;
            mPullDownOffset = dyUnconsumed;
        }
    }

    @Override
    public boolean onLayoutChild(@NonNull CoordinatorLayout parent, @NonNull View child, int layoutDirection) {
        if (!mLayoutChangeListenerAdded) {
            parent.addOnLayoutChangeListener(mOnLayoutChangeListener);
            mLayoutChangeListenerAdded = true;
        }
        if (mParent == null) {
            mParent = parent;
        }
        return false;
    }

    private boolean mLayoutChangeListenerAdded;
    private View.OnLayoutChangeListener mOnLayoutChangeListener = new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {

            View headerView = getHeaderView();
            View secondFloorView = getSecondFloorView();
            View firstFloorView = getFirstFloorView();

            if (headerView.getHeight() > 0) {
                //滑动偏移量没有指定的话，就给一个默认的
                if (mMinTriggerDistance == 0) {
                    mMinTriggerDistance = headerView.getHeight() / 2;
                }
                if (mStartInterceptDistance == 0) {
                    mStartInterceptDistance = headerView.getHeight();
                }
            }
            //HeaderVIew放在一楼的顶部
            int headerBottom = firstFloorView.getTop();
            int headerTop = headerBottom - headerView.getHeight();
            headerView.layout(headerView.getLeft(), headerTop, headerView.getRight(), headerBottom);
            //二楼放在HeaderView的顶部
            int secondFloorTop = headerTop - secondFloorView.getHeight();
            secondFloorView.layout(secondFloorView.getLeft(), secondFloorTop, secondFloorView.getRight(), headerTop);
        }
    };

    private void fakeScroll(View target, float verticalScrollBy, MotionEvent originEvent) {

        float startX = originEvent.getRawX();
        //noinspection UnnecessaryLocalVariable
        float endX = startX;
        float startY = originEvent.getRawY();
        float endY = startY + verticalScrollBy;

        MotionEvent event = reassignEventId(originEvent, mLastDispatchPointerId, MotionEvent.ACTION_MOVE, endX, endY);

        float offsetX = mLastDispatchX - startX;
        float offsetY = mLastDispatchY - startY;

        event.offsetLocation(offsetX, offsetY);

        target.dispatchTouchEvent(event);

        event.setAction(MotionEvent.ACTION_UP);
        target.dispatchTouchEvent(event);

        event.recycle();
    }

    private void onStateChange(int newState) {
        mState = newState;
        if (mOnStateChangeListener != null) {
            mOnStateChangeListener.onStateChange(newState);
        }
    }

    private View getHeaderView() {
        return getChildAt(0, "HeaderView not found! Does your CoordinatorLayout have more than 1 child?");
    }

    private View getSecondFloorView() {
        return getChildAt(1, "SecondFloorView not found! Does your CoordinatorLayout have more than 2 child?");
    }

    private View getFirstFloorView() {
        return getChildAt(2, "FirstFloorView not found! Does your CoordinatorLayout have more than 3 child?");
    }

    @NonNull
    private View getChildAt(int index, String exceptionMessage) {
        if (mParent == null) {
            throwException("SecondFloorBehavior not initialized!");
        }
        View child = mParent.getChildAt(index);
        if (child == null) {
            throwException(exceptionMessage);
        }
        return child;
    }

    private void throwException(String message) {
        throw new IllegalStateException(message);
    }

    /**
     * 重新分配事件id
     *
     * @param originEvent 原事件
     * @param newId       新id
     * @param action      新action
     * @param x           新rawX
     * @param y           新rawY
     * @return 基于原事件和指定变量重新创建的事件
     */
    private MotionEvent reassignEventId(MotionEvent originEvent, int newId, int action, float x, float y) {

        MotionEvent.PointerProperties[] pointerProperties = new MotionEvent.PointerProperties[]{new MotionEvent.PointerProperties()};
        MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[]{new MotionEvent.PointerCoords()};

        pointerProperties[0].id = newId;

        pointerCoords[0].x = x;
        pointerCoords[0].y = y;
        pointerCoords[0].pressure = originEvent.getPressure();
        pointerCoords[0].size = originEvent.getSize();
        pointerCoords[0].orientation = originEvent.getOrientation();
        pointerCoords[0].toolMajor = originEvent.getToolMajor();
        pointerCoords[0].toolMinor = originEvent.getToolMinor();
        pointerCoords[0].touchMajor = originEvent.getTouchMajor();
        pointerCoords[0].touchMinor = originEvent.getTouchMinor();

        return MotionEvent.obtain(originEvent.getDownTime(), SystemClock.uptimeMillis(), action,
                1, pointerProperties, pointerCoords, originEvent.getMetaState(),
                originEvent.getButtonState(), originEvent.getXPrecision(), originEvent.getYPrecision(),
                originEvent.getDeviceId(), originEvent.getEdgeFlags(), originEvent.getSource(), originEvent.getFlags());
    }

    public void setOnBeforeEnterSecondFloorListener(OnBeforeEnterSecondFloorListener listener) {
        mOnBeforeEnterSecondFloorListener = listener;
    }

    public void setOnEnterSecondFloorListener(OnEnterSecondFloorListener listener) {
        mOnEnterSecondFloorListener = listener;
    }

    public void setOnExitSecondFloorListener(OnExitSecondFloorListener listener) {
        mOnExitSecondFloorListener = listener;
    }

    public void setOnStateChangeListener(OnStateChangeListener listener) {
        mOnStateChangeListener = listener;
    }

    public float getStartInterceptDistance() {
        return mStartInterceptDistance;
    }

    public void setStartInterceptDistance(float distance) {
        mStartInterceptDistance = distance;
    }

    public float getMinTriggerDistance() {
        return mMinTriggerDistance;
    }

    public void setMinTriggerDistance(float distance) {
        mMinTriggerDistance = distance;
    }

    public float getDampingRatio() {
        return mDampingRatio;
    }

    public void setDampingRatio(float ratio) {
        mDampingRatio = ratio;
    }

    public long getRollbackDuration() {
        return mRollbackDuration;
    }

    public void setRollbackDuration(long duration) {
        mRollbackDuration = duration;
    }

    public long getEnterDuration() {
        return mEnterDuration;
    }

    public void setEnterDuration(long duration) {
        mEnterDuration = duration;
    }

    public long getExitDuration() {
        return mExitDuration;
    }

    public void setExitDuration(long duration) {
        mExitDuration = duration;
    }

    public int getState() {
        return mState;
    }


    interface OnBeforeEnterSecondFloorListener {
        /**
         * 进入二楼之前
         *
         * @return 是否允许本次进入二楼，true: 允许，false: 拒绝
         */
        boolean onBeforeEnterSecondFloor();
    }

    interface OnEnterSecondFloorListener {
        /**
         * 进入二楼
         */
        void onEnterSecondFloor();
    }

    interface OnExitSecondFloorListener {
        /**
         * 退出二楼
         */
        void onExitSecondFloor();
    }

    interface OnStateChangeListener {
        /**
         * 状态变更
         *
         * @param state 新状态
         */
        void onStateChange(int state);
    }

    /**
     * 参考自 {@link View.DeclaredOnClickListener}
     */
    @SuppressWarnings("JavadocReference")
    private static class DeclaredHelper {

        private final Context mContext;
        private final String mMethodName;
        private final String mExceptionMessage;

        private Method mResolvedMethod;
        private Context mResolvedContext;

        public DeclaredHelper(@NonNull Context context, @NonNull String methodName, @NonNull String exceptionMessage) {
            mContext = context;
            mMethodName = methodName;
            mExceptionMessage = exceptionMessage;
        }

        public void invoke() {
            if (mResolvedMethod == null) {
                resolveMethod(mContext, mMethodName);
            }

            try {
                mResolvedMethod.invoke(mResolvedContext);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Could not execute non-public method for " + mExceptionMessage, e);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException("Could not execute method for " + mExceptionMessage, e);
            }
        }

        private void resolveMethod(@Nullable Context context, @NonNull String name) {
            while (context != null) {
                try {
                    if (!context.isRestricted()) {
                        mResolvedMethod = context.getClass().getMethod(mMethodName);
                        mResolvedContext = context;
                        return;
                    }
                } catch (NoSuchMethodException e) {
                    // Failed to find method, keep searching up the hierarchy.
                }

                if (context instanceof ContextWrapper) {
                    context = ((ContextWrapper) context).getBaseContext();
                } else {
                    // Can't search up the hierarchy, null out and fail.
                    context = null;
                }
            }
            throw new IllegalStateException("Could not find method " + mMethodName
                    + " in a parent or ancestor Context for " + mExceptionMessage);
        }
    }
}
