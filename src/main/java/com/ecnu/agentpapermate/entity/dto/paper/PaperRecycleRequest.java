package com.ecnu.agentpapermate.entity.dto.paper;

import lombok.Getter;
import lombok.Setter;

/**
 * 论文回收与恢复请求。
 */
@Getter
@Setter
public class PaperRecycleRequest {
    /**
     * 论文 ID，必填。
     */
    private Long id;

    /**
     * 目标回收状态：0恢复、1回收，必填。
     */
    private Byte isRecycled;
}
