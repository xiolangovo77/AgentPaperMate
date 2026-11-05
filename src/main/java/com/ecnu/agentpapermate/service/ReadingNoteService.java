package com.ecnu.agentpapermate.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.ecnu.agentpapermate.entity.dto.readingnote.ReadingNoteAddRequest;
import com.ecnu.agentpapermate.entity.dto.readingnote.ReadingNoteUpdateRequest;
import com.ecnu.agentpapermate.entity.model.ReadingNote;
import com.ecnu.agentpapermate.entity.vo.readingnote.ReadingNoteVO;

/**
 * 阅读笔记服务。
 */
public interface ReadingNoteService extends IService<ReadingNote> {

    /** 为当前用户的论文新增笔记，返回笔记 ID。 */
    Long addReadingNote(ReadingNoteAddRequest request, Long userId);

    /** 按客户端读取的版本更新已填写的栏目，返回新版本。 */
    int updateReadingNote(ReadingNoteUpdateRequest request, Long userId);

    /** 查询当前用户的论文笔记，尚未创建时返回 null。 */
    ReadingNoteVO getReadingNote(Long paperId, Long userId);
}
