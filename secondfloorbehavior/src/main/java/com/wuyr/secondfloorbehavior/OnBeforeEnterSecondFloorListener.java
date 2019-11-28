package com.wuyr.secondfloorbehavior;

/**
 * @author wuyr
 * @github https://github.com/wuyr/SecondFloorBehavior
 * @since 2019-11-29 上午1:50
 */
public interface OnBeforeEnterSecondFloorListener {
    /**
     * 进入二楼之前
     *
     * @return 是否允许本次进入二楼，true: 允许，false: 拒绝
     */
    boolean onBeforeEnterSecondFloor();
}
