package com.ecnu.agentpapermate.service;

import com.ecnu.agentpapermate.entity.model.Paper;
import com.baomidou.mybatisplus.spring.service.IService;
import com.ecnu.agentpapermate.entity.dto.paper.PaperAddRequest;
import com.ecnu.agentpapermate.entity.vo.paper.PaperAddVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecnu.agentpapermate.entity.dto.paper.PaperQueryRequest;
import com.ecnu.agentpapermate.entity.vo.paper.PaperDetailVO;
import com.ecnu.agentpapermate.entity.vo.paper.PaperSummaryVO;

/**
 * <p>
 * 论文及原始文件信息 服务类
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
public interface PaperService extends IService<Paper> {

    /** 分页查询当前用户的有效论文。 */
    Page<PaperSummaryVO> listPapers(PaperQueryRequest request, Long userId);

    /** 查询论文详情、问答可用性及最新处理错误。 */
    PaperDetailVO getPaper(Long id, Long userId);

    /** 校验论文归属，仅供服务端读取文件及关联任务使用。 */
    Paper requireOwnedPaper(Long id, Long userId);

    /**
     * 保存论文，并创建论文处理任务和待投递事件。
     *
     * @param request 论文保存请求
     * @param userId  当前登录用户 ID
     * @return 论文保存结果
     */
    PaperAddVO addPaper(PaperAddRequest request, Long userId);
}
