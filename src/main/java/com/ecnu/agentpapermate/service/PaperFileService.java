package com.ecnu.agentpapermate.service;

import com.ecnu.agentpapermate.entity.model.Paper;
import com.ecnu.agentpapermate.mange.CosManager;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.ObjectMetadata;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 校验论文归属并流式输出 PDF，支持单段字节范围读取。 */
@Service
@RequiredArgsConstructor
public class PaperFileService {
    /**
     * 论文业务服务，在访问存储之前校验论文归属和有效性。
     */
    private final PaperService paperService;
    /**
     * COS 文件服务，读取可信元数据并按指定字节区间打开文件流。
     */
    private final CosManager cosManager;

    public void writeFile(Long id, Long userId, String range, String ifRange, HttpServletResponse response)
            throws IOException {
        //1、先校验论文权限再读取元数据，长度使用 COS 实际值
        Paper paper = paperService.requireOwnedPaper(id, userId);
        ObjectMetadata metadata = cosManager.getPaperMetadata(paper.getFileUrl(), userId);
        long length = metadata.getContentLength();
        long start = 0;
        long end = length - 1;
        boolean partial = false;

        //2、支持闭区间、开放结束位置与后缀范围；多段或 If-Range 请求保守返回完整文件
        if (range != null && ifRange == null) {
            try {
                List<HttpRange> ranges = HttpRange.parseRanges(range);
                if (ranges.size() == 1) {
                    start = ranges.getFirst().getRangeStart(length);
                    end = ranges.getFirst().getRangeEnd(length);
                    if (start >= length || start > end) throw new IllegalArgumentException("unsatisfiable range");
                    partial = true;
                }
            } catch (IllegalArgumentException e) {
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + length);
                response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
                response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
                response.setContentLengthLong(0);
                return;
            }
        }

        //3、成功打开且复核对象归属后才设置 PDF 响应，避免存储错误被当作文件下载
        try (COSObject object = cosManager.openPaperRange(paper.getFileUrl(), userId, start, end, metadata.getETag())) {
            response.setStatus(partial ? HttpServletResponse.SC_PARTIAL_CONTENT : HttpServletResponse.SC_OK);
            response.setContentType("application/pdf");
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            // 文件名固定由可信论文 ID 构成，避免换行、路径字符进入响应头。
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                    .filename("paper-" + paper.getId() + ".pdf", StandardCharsets.UTF_8).build().toString());
            if (partial) response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
            long remaining = end - start + 1;
            response.setContentLengthLong(remaining);

            //4、使用固定缓冲区传输并检查截断，断开连接或失败时 try-with-resources 释放 COS 连接
            InputStream input = object.getObjectContent();
            byte[] buffer = new byte[8192];
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new IOException("论文文件流提前结束");
                response.getOutputStream().write(buffer, 0, count);
                remaining -= count;
            }
        }
    }
}
