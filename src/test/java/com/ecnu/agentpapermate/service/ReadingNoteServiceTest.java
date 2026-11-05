package com.ecnu.agentpapermate.service;

import com.ecnu.agentpapermate.entity.dto.readingnote.ReadingNoteAddRequest;
import com.ecnu.agentpapermate.entity.dto.readingnote.ReadingNoteUpdateRequest;
import com.ecnu.agentpapermate.entity.model.ReadingNote;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.mapper.PaperMapper;
import com.ecnu.agentpapermate.mapper.ReadingNoteMapper;
import com.ecnu.agentpapermate.service.impl.ReadingNoteServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadingNoteServiceTest {
    private final PaperMapper papers = mock(PaperMapper.class);
    private final ReadingNoteMapper notes = mock(ReadingNoteMapper.class);
    private final ReadingNoteServiceImpl service = new ReadingNoteServiceImpl(papers);
    private final ReadingNoteAddRequest request = new ReadingNoteAddRequest();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseMapper", notes);
        request.setPaperId(20L);
        request.setExperimentResults("实验结果");
    }

    @Test
    void createsVersionOneAndReadsItByOwnerAndPaper() {
        when(papers.exists(any())).thenReturn(true);
        when(notes.insert(any(ReadingNote.class))).thenAnswer(invocation -> {
            ReadingNote note = invocation.getArgument(0);
            assertEquals(10L, note.getUserId());
            assertEquals(20L, note.getPaperId());
            note.setId(30L);
            when(notes.getByUserAndPaper(10L, 20L)).thenReturn(note);
            return 1;
        });
        assertNull(service.getReadingNote(20L, 10L));
        assertEquals(30L, service.addReadingNote(request, 10L));
        var result = service.getReadingNote(20L, 10L);
        assertEquals(30L, result.getId());
        assertEquals(1, result.getVersion());
        assertEquals("实验结果", result.getExperimentResults());
    }

    @Test
    void deniesAccessWhenPaperIsUnavailableToUser() {
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(),
                assertThrows(BusinessException.class, () -> service.addReadingNote(request, 10L)).getCode());
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(),
                assertThrows(BusinessException.class, () -> service.getReadingNote(20L, 10L)).getCode());
        verifyNoInteractions(notes);
    }

    private ReadingNoteUpdateRequest edit() {
        ReadingNoteUpdateRequest edit = new ReadingNoteUpdateRequest();
        edit.setPaperId(20L);
        edit.setVersion(1);
        edit.setMethods("");
        return edit;
    }

    @Test
    void returnsNewVersionAndRejectsRetryWithOldVersion() {
        var edit = edit();
        when(papers.exists(any())).thenReturn(true);
        when(notes.updateByVersion(10L, edit)).thenReturn(1, 0);
        when(notes.getByUserAndPaper(10L, 20L)).thenReturn(new ReadingNote());

        assertEquals(2, service.updateReadingNote(edit, 10L));
        assertEquals(ErrorCode.CONFLICT_ERROR.getCode(),
                assertThrows(BusinessException.class, () -> service.updateReadingNote(edit, 10L)).getCode());
    }

    @Test
    void rejectsUnavailablePaperAndMissingNote() {
        var edit = edit();
        when(papers.exists(any())).thenReturn(false, true);
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(),
                assertThrows(BusinessException.class, () -> service.updateReadingNote(edit, 10L)).getCode());
        verifyNoInteractions(notes);
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(),
                assertThrows(BusinessException.class, () -> service.updateReadingNote(edit, 10L)).getCode());
    }

    @Test
    void rejectsMissingVersionAndEmptyPatch() {
        var edit = edit();
        when(papers.exists(any())).thenReturn(true);
        edit.setVersion(null);
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(),
                assertThrows(BusinessException.class, () -> service.updateReadingNote(edit, 10L)).getCode());
        edit.setVersion(1);
        edit.setMethods(null);
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(),
                assertThrows(BusinessException.class, () -> service.updateReadingNote(edit, 10L)).getCode());
        verifyNoInteractions(notes);
    }

    @Test
    void duplicateInsertReportsBusinessError() {
        when(papers.exists(any())).thenReturn(true);
        when(notes.insert(any(ReadingNote.class))).thenThrow(new DuplicateKeyException("uk_note_user_paper"));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(),
                assertThrows(BusinessException.class, () -> service.addReadingNote(request, 10L)).getCode());
    }
}
