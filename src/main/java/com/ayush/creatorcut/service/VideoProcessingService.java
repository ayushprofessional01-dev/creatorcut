package com.ayush.creatorcut.service;

import com.ayush.creatorcut.model.ProcessingResult;
import com.ayush.creatorcut.model.SilenceSegment;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class VideoProcessingService {

    private static final String PROCESSED_DIR = "processed";

    public ProcessingResult processVideo(
            String inputFilePath,
            String originalFileName,
            String sensitivity,
            double minSilenceDuration)
            throws IOException, InterruptedException {

        Path processedPath = Paths.get(PROCESSED_DIR);

        if (!Files.exists(processedPath)) {
            Files.createDirectories(processedPath);
        }

        List<SilenceSegment> silenceSegments = detectSilence(
                inputFilePath,
                sensitivity,
                minSilenceDuration
        );

        String safeFileName = Paths.get(originalFileName).getFileName().toString();

        String outputFileName = "trimmed_" + safeFileName;

        String outputFilePath = PROCESSED_DIR + File.separator + outputFileName;

        removeSilence(inputFilePath, outputFilePath, silenceSegments);

        return new ProcessingResult(outputFileName, silenceSegments);
    }

    private List<SilenceSegment> detectSilence(
            String inputFilePath,
            String sensitivity,
            double minSilenceDuration)
            throws IOException, InterruptedException {

        List<SilenceSegment> silenceSegments = new ArrayList<>();

        String noiseLevel = getNoiseLevel(sensitivity);

        String silenceFilter = "silencedetect=n=" + noiseLevel + ":d=" + minSilenceDuration;

        ProcessBuilder processBuilder = new ProcessBuilder(
                "ffmpeg",
                "-i", inputFilePath,
                "-af", silenceFilter,
                "-f", "null",
                "-"
        );

        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
        );

        String line;
        Double currentSilenceStart = null;

        while ((line = reader.readLine()) != null) {

            if (line.contains("silence_start:")) {
                currentSilenceStart = extractValue(line, "silence_start:");
            }

            if (line.contains("silence_end:")) {
                Double silenceEnd = extractValue(line, "silence_end:");

                if (currentSilenceStart != null && silenceEnd != null) {

                    double start = Math.max(0, currentSilenceStart);
                    double end = silenceEnd;
                    double duration = end - start;

                    if (duration >= minSilenceDuration) {
                        silenceSegments.add(
                                new SilenceSegment(start, end)
                        );
                    }

                    currentSilenceStart = null;
                }
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Silence detection failed.");
        }

        return silenceSegments;
    }

    private void removeSilence(
            String inputFilePath,
            String outputFilePath,
            List<SilenceSegment> silenceSegments)
            throws IOException, InterruptedException {

        ProcessBuilder processBuilder;

        if (silenceSegments.isEmpty()) {

            processBuilder = new ProcessBuilder(
                    "ffmpeg",
                    "-y",
                    "-i", inputFilePath,
                    "-c", "copy",
                    outputFilePath
            );

        } else {

            String silenceExpression = buildSilenceExpression(silenceSegments);

            String removeExpression = "not(" + silenceExpression + ")";

            String videoFilter = "select='" + removeExpression + "',setpts=N/FRAME_RATE/TB";

            String audioFilter = "aselect='" + removeExpression + "',asetpts=N/SR/TB";

            processBuilder = new ProcessBuilder(
                    "ffmpeg",
                    "-y",
                    "-i", inputFilePath,
                    "-vf", videoFilter,
                    "-af", audioFilter,
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-c:a", "aac",
                    outputFilePath
            );
        }

        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        Process process = processBuilder.start();

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Silence removal failed.");
        }
    }

    private String buildSilenceExpression(List<SilenceSegment> silenceSegments) {

        return silenceSegments.stream()
                .map(segment -> String.format(
                        Locale.US,
                        "between(t,%.3f,%.3f)",
                        segment.getStart(),
                        segment.getEnd()
                ))
                .collect(Collectors.joining("+"));
    }

    private String getNoiseLevel(String sensitivity) {

        if (sensitivity == null) {
            return "-25dB";
        }

        return switch (sensitivity.toLowerCase()) {
            case "low" -> "-35dB";
            case "medium" -> "-30dB";
            case "high" -> "-25dB";
            default -> "-25dB";
        };
    }

    private Double extractValue(String line, String key) {

        int startIndex = line.indexOf(key);

        if (startIndex == -1) {
            return null;
        }

        String valuePart = line.substring(startIndex + key.length()).trim();

        String number = valuePart.split("\\s+")[0];

        return Double.parseDouble(number);
    }
}