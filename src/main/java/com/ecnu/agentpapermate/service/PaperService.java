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
    /**
     * 回收或恢复当前用户的论文，保留已有会话关联及论文数据。
     *
     * @param id 论文 ID
     * @param isRecycled 目标回收状态：0恢复、1回收
     * @param userId 当前登录用户 ID，用于校验论文归属
     * @return 操作成功返回 true，校验或操作失败时抛出异常
     */
    boolean setRecycled(Long id, Byte isRecycled, Long userId);

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
