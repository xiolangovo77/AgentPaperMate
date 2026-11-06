package com.ecnu.agentpapermate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecnu.agentpapermate.entity.model.MessageCitation;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 回答原文引用快照 Mapper 接口
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@Mapper
public interface MessageCitationMapper extends BaseMapper<MessageCitation> {

}
