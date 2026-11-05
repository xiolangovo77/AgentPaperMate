package com.ecnu.agentpapermate.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ecnu.agentpapermate.entity.dto.readingnote.ReadingNoteAddRequest;
import com.ecnu.agentpapermate.entity.dto.readingnote.ReadingNoteUpdateRequest;
import com.ecnu.agentpapermate.entity.model.Paper;
import com.ecnu.agentpapermate.entity.model.ReadingNote;
import com.ecnu.agentpapermate.entity.vo.readingnote.ReadingNoteVO;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.exception.ThrowUtils;
import com.ecnu.agentpapermate.mapper.PaperMapper;
import com.ecnu.agentpapermate.mapper.ReadingNoteMapper;
import com.ecnu.agentpapermate.service.ReadingNoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 阅读笔记服务实现类。
 */
@Service
@RequiredArgsConstructor
public class ReadingNoteServiceImpl extends ServiceImpl<ReadingNoteMapper, ReadingNote> implements ReadingNoteService {

    private final PaperMapper paperMapper;

    @Override
    public Long addReadingNote(ReadingNoteAddRequest request, Long userId) {
        // 1、校验新增请求，确认论文属于当前登录用户
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        checkPaper(request.getPaperId(), userId);

        // 2、填入笔记内容和所有者，将初始版本设为 1
        ReadingNote note = new ReadingNote();
        BeanUtils.copyProperties(request, note);
        note.setUserId(userId);
        note.setVersion(1);

        // 3、保存阅读笔记并返回笔记 ID
        try {
            ThrowUtils.throwIf(!save(note), ErrorCode.OPERATION_ERROR, "新增阅读笔记失败");
        } catch (DuplicateKeyException e) {
            // 同一用户和论文的唯一索引同时约束普通提交和并发提交。
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该论文已存在阅读笔记");
        }
        return note.getId();
    }

    @Override
    public int updateReadingNote(ReadingNoteUpdateRequest request, Long userId) {
        // 1、校验更新请求，确认论文属于当前登录用户
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        checkPaper(request.getPaperId(), userId);

        // 2、校验笔记版本，并确认至少提交了一个要修改的栏目
        Integer version = request.getVersion();
        ThrowUtils.throwIf(version == null || version <= 0 || version == Integer.MAX_VALUE,
                ErrorCode.PARAMS_ERROR, "笔记版本无效");
        ThrowUtils.throwIf(request.getResearchQuestion() == null && request.getMethods() == null
                        && request.getInnovations() == null && request.getExperimentResults() == null
                        && request.getLimitationsQuestions() == null && request.getPersonalThoughts() == null,
                ErrorCode.PARAMS_ERROR, "请至少提供一个笔记栏目");

        // 3、按提交的版本更新笔记，未更新时区分笔记不存在和版本冲突
        if (baseMapper.updateByVersion(userId, request) == 0) {
            ThrowUtils.throwIf(baseMapper.getByUserAndPaper(userId, request.getPaperId()) == null,
                    ErrorCode.NOT_FOUND_ERROR, "阅读笔记不存在");
            throw new BusinessException(ErrorCode.CONFLICT_ERROR, "笔记已更新，请刷新后重新编辑");
        }

        // 4、返回保存后的版本号，供下次更新使用
        return version + 1;
    }

    @Override
    public ReadingNoteVO getReadingNote(Long paperId, Long userId) {
        // 1、校验论文 ID，确认论文属于当前登录用户
        checkPaper(paperId, userId);

        // 2、按用户和论文查询笔记，尚未创建时返回 null
        ReadingNote note = baseMapper.getByUserAndPaper(userId, paperId);
        if (note == null) {
            return null;
        }

        // 3、将笔记转换为对外返回的详情
        ReadingNoteVO result = new ReadingNoteVO();
        BeanUtils.copyProperties(note, result);
        return result;
    }

    /** 校验论文存在且属于当前登录用户。 */
    private void checkPaper(Long paperId, Long userId) {
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(paperId == null || paperId <= 0, ErrorCode.PARAMS_ERROR, "论文 ID 无效");
        boolean exists = paperMapper.exists(new QueryWrapper<Paper>().eq("id", paperId).eq("user_id", userId));
        ThrowUtils.throwIf(!exists, ErrorCode.NOT_FOUND_ERROR, "论文不存在");
    }
}
