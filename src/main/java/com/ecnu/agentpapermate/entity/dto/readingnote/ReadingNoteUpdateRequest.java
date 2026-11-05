package com.ecnu.agentpapermate.entity.dto.readingnote;

import lombok.Getter;
import lombok.Setter;

/** 更新阅读笔记。未提供的栏目跳过，空字符串用于清空栏目。 */
@Getter
@Setter
public class ReadingNoteUpdateRequest extends ReadingNoteAddRequest {

    /** 编辑时读取到的笔记版本。 */
    private Integer version;
}
