package com.ecnu.agentpapermate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecnu.agentpapermate.entity.dto.readingnote.ReadingNoteUpdateRequest;
import com.ecnu.agentpapermate.entity.model.ReadingNote;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 阅读笔记 Mapper 接口。
 */
@Mapper
public interface ReadingNoteMapper extends BaseMapper<ReadingNote> {

    /** 查询当前用户在指定论文下的有效笔记。 */
    ReadingNote getByUserAndPaper(@Param("userId") Long userId, @Param("paperId") Long paperId);

    int updateByVersion(@Param("userId") Long userId, @Param("note") ReadingNoteUpdateRequest note);
}
