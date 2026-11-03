package com.ecnu.agentpapermate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecnu.agentpapermate.entity.model.Paper;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 论文及原始文件信息 Mapper 接口
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
@Mapper
public interface PaperMapper extends BaseMapper<Paper> {

}
