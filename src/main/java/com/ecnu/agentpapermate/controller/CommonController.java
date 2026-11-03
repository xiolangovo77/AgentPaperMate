package com.ecnu.agentpapermate.controller;

import com.ecnu.agentpapermate.common.BaseResponse;
import com.ecnu.agentpapermate.common.ResultUtils;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.exception.ThrowUtils;
import com.ecnu.agentpapermate.mange.CosManager;
import com.ecnu.agentpapermate.service.AppUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 公共文件上传接口，原样保存头像和论文文件。
 */
@RestController
@RequestMapping("/common")
@RequiredArgsConstructor
@Slf4j
public class CommonController {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final long AVATAR_MAX_SIZE = 2 * 1024 * 1024L;
    private static final long PAPER_MAX_SIZE = 50 * 1024 * 1024L;
    private static final Set<String> AVATAR_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    private final CosManager cosManager;
    private final AppUserService appUserService;

    /**
     * 上传头像，返回 URL；头像信息由用户更新接口另行保存。
     */
    @PostMapping(value = "/upload/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BaseResponse<String> uploadAvatar(@RequestPart("file") MultipartFile file,
                                              HttpServletRequest request) {
        appUserService.getLoginUser(request);
        String extension = validateFile(file, AVATAR_MAX_SIZE, AVATAR_EXTENSIONS,
                "头像仅支持 JPG、JPEG、PNG、WEBP，大小不能超过 2 MB");
        return ResultUtils.success(upload(file, "avatars", extension));
    }

    /**
     * 上传 PDF 论文，返回 URL。
     */
    @PostMapping(value = "/upload/paper", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BaseResponse<String> uploadPaper(@RequestPart("file") MultipartFile file,
                                             HttpServletRequest request) {
        appUserService.getLoginUser(request);
        String extension = validateFile(file, PAPER_MAX_SIZE, Set.of("pdf"),
                "论文仅支持 PDF，大小不能超过 50 MB");
        return ResultUtils.success(upload(file, "papers", extension));
    }

    private String validateFile(MultipartFile file, long maxSize, Set<String> extensions, String message) {
        ThrowUtils.throwIf(file == null || file.isEmpty(), ErrorCode.PARAMS_ERROR, "上传文件不能为空");
        ThrowUtils.throwIf(file.getSize() > maxSize, ErrorCode.PARAMS_ERROR, message);
        String filename = file.getOriginalFilename();
        String extension = filename == null ? "" : filename.substring(filename.lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT);
        ThrowUtils.throwIf(filename == null || filename.lastIndexOf('.') < 0 || !extensions.contains(extension),
                ErrorCode.PARAMS_ERROR, message);
        // 检查文件头，不信任客户端声明的 Content-Type；不解码或重编码文件。
        try (InputStream input = file.getInputStream()) {
            ThrowUtils.throwIf(!matchesHeader(input.readNBytes(12), extension),
                    ErrorCode.PARAMS_ERROR, "文件内容与扩展名不匹配");
        } catch (IOException e) {
            log.error("读取上传文件失败", e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "读取上传文件失败，请重试");
        }
        return extension;
    }

    private boolean matchesHeader(byte[] header, String extension) {
        return switch (extension) {
            case "jpg", "jpeg" -> startsWith(header, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
            case "png" -> startsWith(header, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "webp" -> header.length >= 12
                    && startsWith(header, "RIFF".getBytes(StandardCharsets.US_ASCII))
                    && new String(header, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
            case "pdf" -> startsWith(header, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            default -> false;
        };
    }

    private boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (content[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private String upload(MultipartFile file, String directory, String extension) {
        String key = directory + "/" + LocalDate.now().format(DATE_FORMATTER) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path temporaryFile = null;
        try {
            temporaryFile = Files.createTempFile("cos-upload-", "." + extension);
            file.transferTo(temporaryFile);
            String url = cosManager.uploadFile(key, temporaryFile.toFile());
            ThrowUtils.throwIf(url == null || url.isBlank(), ErrorCode.OPERATION_ERROR, "文件上传失败，请重试");
            return url;
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            log.error("文件上传 COS 失败，key={}", key, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "文件上传失败，请重试");
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException e) {
                    log.warn("清理上传临时文件失败：{}", temporaryFile, e);
                }
            }
        }
    }
}
