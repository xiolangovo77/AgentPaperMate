package com.ecnu.agentpapermate.mange;

import com.ecnu.agentpapermate.config.CosClientConfig;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.exception.ThrowUtils;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.ObjectMetadata;
import lombok.RequiredArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * COS对象存储管理器
 *
 * @author yupi
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CosManager {

    /**
     * 论文文件大小上限，单位为字节。
     */
    public static final long PAPER_MAX_SIZE = 50 * 1024 * 1024L;

    /**
     * COS 自定义元数据中保存原始文件名的键。
     */
    private static final String ORIGINAL_FILENAME = "original-filename-base64";

    /**
     * COS 自定义元数据中保存上传用户 ID 的键。
     */
    private static final String OWNER_ID = "owner-id";

    /**
     * COS 存储桶和访问域名配置。
     */
    private final CosClientConfig cosClientConfig;

    /**
     * COS SDK 客户端。
     */
    private final COSClient cosClient;

    /**
     * 上传论文并保存原始文件名和上传用户 ID，文件名使用 Base64 编码。
     *
     * @param key              COS 对象键
     * @param file             待上传文件
     * @param originalFilename 上传时的原始文件名
     * @param userId           当前登录用户 ID
     * @return 文件的固定访问 URL
     */
    public String uploadPaper(String key, File file, String originalFilename, Long userId) {
        ThrowUtils.throwIf(originalFilename == null || originalFilename.isBlank()
                        || originalFilename.length() > 255,
                ErrorCode.PARAMS_ERROR, "原始文件名不能为空且不能超过255个字符");
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType("application/pdf");
        metadata.addUserMetadata(ORIGINAL_FILENAME, Base64.getEncoder()
                .encodeToString(originalFilename.getBytes(StandardCharsets.UTF_8)));
        metadata.addUserMetadata(OWNER_ID, userId.toString());
        PutObjectResult result = cosClient.putObject(new PutObjectRequest(
                cosClientConfig.getBucket(), key, file).withMetadata(metadata));
        ThrowUtils.throwIf(result == null, ErrorCode.OPERATION_ERROR, "文件上传失败，请重试");
        return cosClientConfig.getHost().replaceAll("/+$", "") + "/" + key;
    }

    /**
     * 根据固定 URL 定位 COS 对象，校验文件归属并读取文件信息。
     * 通过 COS SDK 读取，不向用户传入的 URL 发起 HTTP 请求。
     *
     * @param fileUrl 上传接口返回的固定文件 URL
     * @param userId  当前登录用户 ID
     * @return 已校验的文件名、大小和 SHA-256 哈希
     */
    public PaperFileInfo readPaper(String fileUrl, Long userId) {
        return readPaper(fileUrl, userId, null);
    }

    public PaperFileInfo downloadPaper(String fileUrl, Long userId, Path destination) {
        return readPaper(fileUrl, userId, destination);
    }

    private PaperFileInfo readPaper(String fileUrl, Long userId, Path destination) {
        String prefix = cosClientConfig.getHost().replaceAll("/+$", "") + "/";
        ThrowUtils.throwIf(fileUrl == null || fileUrl.length() > 2048 || !fileUrl.startsWith(prefix),
                ErrorCode.PARAMS_ERROR, "请使用本系统上传后返回的固定文件URL");
        String key = fileUrl.substring(prefix.length());
        ThrowUtils.throwIf(!key.matches("papers/\\d{4}/\\d{2}/\\d{2}/[a-f0-9]{32}\\.pdf"),
                ErrorCode.PARAMS_ERROR, "论文文件URL格式不正确，不允许临时签名或其他路径");
        try (COSObject object = cosClient.getObject(cosClientConfig.getBucket(), key);
             OutputStream output = destination == null ? OutputStream.nullOutputStream() : Files.newOutputStream(destination)) {
            ObjectMetadata metadata = object.getObjectMetadata();
            ThrowUtils.throwIf(!userId.toString().equals(metadata.getUserMetaDataOf(OWNER_ID)),
                    ErrorCode.NO_AUTH_ERROR, "文件不属于当前用户或缺少上传信息，请重新上传");
            String encodedName = metadata.getUserMetaDataOf(ORIGINAL_FILENAME);
            ThrowUtils.throwIf(encodedName == null, ErrorCode.PARAMS_ERROR, "文件缺少原始文件名，请重新上传");
            String filename = new String(Base64.getDecoder().decode(encodedName), StandardCharsets.UTF_8);
            ThrowUtils.throwIf(filename.isBlank() || filename.length() > 255,
                    ErrorCode.PARAMS_ERROR, "原始文件名无效，请重新上传");
            long expectedSize = metadata.getContentLength();
            ThrowUtils.throwIf(expectedSize <= 0 || expectedSize > PAPER_MAX_SIZE,
                    ErrorCode.PARAMS_ERROR, "论文大小必须在1字节到50MB之间");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream input = object.getObjectContent();
            byte[] header = input.readNBytes(5);
            ThrowUtils.throwIf(!Arrays.equals(header, "%PDF-".getBytes(StandardCharsets.US_ASCII)),
                    ErrorCode.PARAMS_ERROR, "文件内容不是PDF");
            digest.update(header);
            output.write(header);
            long size = header.length;
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                ThrowUtils.throwIf(size > PAPER_MAX_SIZE, ErrorCode.PARAMS_ERROR, "论文不能超过50MB");
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
            ThrowUtils.throwIf(size != expectedSize, ErrorCode.OPERATION_ERROR, "文件读取不完整，请重试");
            PaperFileInfo result = new PaperFileInfo();
            result.setOriginalFilename(filename);
            result.setFileSize(size);
            result.setFileHash(HexFormat.of().formatHex(digest.digest()));
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("读取COS论文失败，key={}", key, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "读取论文文件失败，请重新上传或稍后重试");
        }
    }

    /**
     * 从 COS 读取并校验后的论文文件信息。
     */
    @Data
    public static class PaperFileInfo {

        /**
         * 上传时的原始文件名。
         */
        private String originalFilename;

        /**
         * 实际读取的文件大小，单位为字节。
         */
        private Long fileSize;

        /**
         * 原始文件字节的 SHA-256 十六进制哈希值。
         */
        private String fileHash;
    }

    /**
     * 上传对象
     *
     * @param key  唯一键
     * @param file 文件
     * @return 上传结果
     */
    public PutObjectResult putObject(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key, file);
        return cosClient.putObject(putObjectRequest);
    }

    /**
     * 上传文件到 COS 并返回访问 URL
     *
     * @param key  COS对象键（完整路径）
     * @param file 要上传的文件
     * @return 文件的访问URL，失败返回null
     */
    public String uploadFile(String key, File file) {
        // 上传文件
        PutObjectResult result = putObject(key, file);
        if (result != null) {
            // 统一域名末尾的分隔符，兼容 host 配置带或不带斜杠。
            String url = cosClientConfig.getHost().replaceAll("/+$", "") + "/" + key;
            log.info("文件上传COS成功: {} -> {}", file.getName(), url);
            return url;
        } else {
            log.error("文件上传COS失败，返回结果为空");
            return null;
        }
    }
}
