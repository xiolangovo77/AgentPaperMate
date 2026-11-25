package com.ecnu.agentpapermate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecnu.agentpapermate.entity.model.Paper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

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

    /**
     * 读取指定账号的有效论文元数据，供论文级索引回填。过滤已回收、已删除或缺少有效索引版本的论文。
     *
     * @param username 论文归属账号的登录名
     * @param paperIds 待回填的业务论文 ID 列表，调用方须提供非空列表
     * @return 符合条件的论文及有效索引版本，无匹配时返回空列表。调用方据此检查是否覆盖全部待回填论文
     */
    List<Paper> selectForLibraryIndex(@Param("username") String username, @Param("paperIds") List<Long> paperIds);
}
