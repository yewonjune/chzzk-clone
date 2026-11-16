package com.chzzk.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 업로드된 영상 파일을 /media/** 로 제공합니다.
 * Spring 의 ResourceHttpRequestHandler 는 HTTP Range 요청을 지원하므로 플레이어에서 구간 이동이 됩니다.
 */
@Configuration
public class MediaConfig implements WebMvcConfigurer {

    private final Path uploadDir;

    public MediaConfig(@Value("${app.upload-dir}") String uploadDir) {
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 폴더를 만들 수 없습니다: " + this.uploadDir, e);
        }
    }

    public Path uploadDir() {
        return uploadDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/media/**")
                .addResourceLocations(uploadDir.toUri().toString());
    }
}
