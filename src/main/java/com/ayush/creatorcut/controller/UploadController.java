package com.ayush.creatorcut.controller;

import com.ayush.creatorcut.model.ProcessingResult;
import com.ayush.creatorcut.service.VideoProcessingService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Controller
public class UploadController {

    private static final String UPLOAD_DIR = "uploads";

    private final VideoProcessingService videoProcessingService;

    public UploadController(VideoProcessingService videoProcessingService) {
        this.videoProcessingService = videoProcessingService;
    }

    @PostMapping("/upload")
    public String uploadVideo(
            @RequestParam("video") MultipartFile file,
            @RequestParam(defaultValue = "high") String sensitivity,
            @RequestParam(defaultValue = "0.3") double minSilenceDuration,
            @RequestParam(defaultValue = "false") boolean enhanceAudio,
            Model model) {

        try {
            if (file.isEmpty()) {
                model.addAttribute("message", "Please select a video file first.");
                return "result";
            }

            Path uploadPath = Paths.get(UPLOAD_DIR);

            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            String fileName = Paths.get(file.getOriginalFilename()).getFileName().toString();

            Path filePath = uploadPath.resolve(fileName);

            Files.copy(
                    file.getInputStream(),
                    filePath,
                    StandardCopyOption.REPLACE_EXISTING
            );

            ProcessingResult processingResult = videoProcessingService.processVideo(
                    filePath.toString(),
                    fileName,
                    sensitivity,
                    minSilenceDuration,
                    enhanceAudio
            );

            model.addAttribute("message", "Video uploaded, silence detected, and processed successfully.");
            model.addAttribute("downloadFileName", processingResult.getOutputFileName());
            model.addAttribute("silences", processingResult.getSilenceSegments());
            model.addAttribute("silenceCount", processingResult.getSilenceSegments().size());
            model.addAttribute("selectedSensitivity", sensitivity);
            model.addAttribute("selectedDuration", minSilenceDuration);
            model.addAttribute("enhanceAudio", enhanceAudio);

        } catch (IOException e) {
            model.addAttribute("message", "Upload failed: " + e.getMessage());
        } catch (InterruptedException e) {
            model.addAttribute("message", "Processing was interrupted.");
        } catch (RuntimeException e) {
            model.addAttribute("message", "Processing failed: " + e.getMessage());
        }

        return "result";
    }
}