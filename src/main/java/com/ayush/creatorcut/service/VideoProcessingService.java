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
            double minSilenceDuration,
            boolean enhanceAudio)
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

        String outputFileName = "processed_" + safeFileName;

        String outputFilePath = PROCESSED_DIR + File.separator + outputFileName;

        processFinalVideo(
                inputFilePath,
                outputFilePath,
                silenceSegments,
                enhanceAudio
        );

        return new ProcessingResult(outputFileName, silenceSegments);
    }

    private List<SilenceSegment> detectSilence(
            String inputFilePath,
            String sensitivity,
            double minSilenceDuration)
            throws IOException, InterruptedException {

        List<SilenceSegment> silenceSegments = new ArrayList<>();

        String noiseLevel = getNoiseLevel(sensitivity);

        String silenceFilter =
                "silencedetect=n=" + noiseLevel + ":d=" + minSilenceDuration;

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
                currentSilenceStart =
                        extractValue(line, "silence_start:");
            }

            if (line.contains("silence_end:")) {
                Double silenceEnd =
                        extractValue(line, "silence_end:");

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

    private void processFinalVideo(
            String inputFilePath,
            String outputFilePath,
            List<SilenceSegment> silenceSegments,
            boolean enhanceAudio)
            throws IOException, InterruptedException {

        ProcessBuilder processBuilder;

        boolean hasSilence = !silenceSegments.isEmpty();

        if (!hasSilence && !enhanceAudio) {

            processBuilder = new ProcessBuilder(
                    "ffmpeg",
                    "-y",
                    "-i", inputFilePath,
                    "-c", "copy",
                    outputFilePath
            );

        } else if (!hasSilence) {

            String audioEnhancementFilter =
                    getAudioEnhancementFilter();

            processBuilder = new ProcessBuilder(
                    "ffmpeg",
                    "-y",
                    "-i", inputFilePath,
                    "-c:v", "copy",
                    "-af", audioEnhancementFilter,
                    "-c:a", "aac",
                    "-b:a", "192k",
                    outputFilePath
            );

        } else {

            /*
             * Build the parts of the video that should be KEPT.
             *
             * Example:
             *
             * Original:
             * 0 -------- silence -------- 5 -------- silence -------- 10
             *
             * We create:
             *
             * Part 0: 0 -> first silence
             * Part 1: second silence -> next silence
             * Part 2: last silence -> end
             *
             * Both audio and video use exactly the same time ranges.
             */

            List<double[]> keptSegments = buildKeptSegments(
                    silenceSegments
            );

            if (keptSegments.isEmpty()) {
                throw new RuntimeException(
                        "No video content remains after removing silence."
                );
            }

            String filterComplex =
                    buildSynchronizedFilterComplex(
                            keptSegments,
                            enhanceAudio
                    );

            processBuilder = new ProcessBuilder(
                    "ffmpeg",
                    "-y",
                    "-i", inputFilePath,
                    "-filter_complex", filterComplex,
                    "-map", "[vout]",
                    "-map", "[aout]",
                    "-c:v", "libx264",
                    "-preset", "ultrafast",
                    "-threads", "1",
                    "-c:a", "aac",
                    "-b:a", "192k",
                    "-movflags", "+faststart",
                    outputFilePath
            );
        }

        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        Process process = processBuilder.start();

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException(
                    "Video processing failed."
            );
        }
    }

    private List<double[]> buildKeptSegments(
            List<SilenceSegment> silenceSegments) {

        List<double[]> keptSegments = new ArrayList<>();

        double previousEnd = 0.0;

        for (SilenceSegment silence : silenceSegments) {

            double silenceStart = silence.getStart();
            double silenceEnd = silence.getEnd();

            if (silenceStart > previousEnd) {
                keptSegments.add(
                        new double[]{
                                previousEnd,
                                silenceStart
                        }
                );
            }

            previousEnd = Math.max(previousEnd, silenceEnd);
        }

        /*
         * The final segment goes from the end of the last silence
         * until the end of the video.
         *
         * We don't specify an end time here because FFmpeg's
         * trim/atrim filters can simply continue until EOF.
         */
        keptSegments.add(
                new double[]{
                        previousEnd,
                        -1
                }
        );

        return keptSegments;
    }

    private String buildSynchronizedFilterComplex(
            List<double[]> keptSegments,
            boolean enhanceAudio) {

        int segmentCount = keptSegments.size();

        StringBuilder filter = new StringBuilder();

        /*
         * Split the original video/audio streams so each kept
         * segment gets its own copy.
         */
        filter.append("[0:v]split=")
                .append(segmentCount);

        for (int i = 0; i < segmentCount; i++) {
            filter.append("[vsrc").append(i).append("]");
        }

        filter.append(";");

        filter.append("[0:a]asplit=")
                .append(segmentCount);

        for (int i = 0; i < segmentCount; i++) {
            filter.append("[asrc").append(i).append("]");
        }

        filter.append(";");

        /*
         * Trim every video and audio segment using the SAME
         * start/end times.
         */
        for (int i = 0; i < segmentCount; i++) {

            double start = keptSegments.get(i)[0];
            double end = keptSegments.get(i)[1];

            filter.append("[vsrc")
                    .append(i)
                    .append("]");

            if (end >= 0) {
                filter.append("trim=start=")
                        .append(formatTime(start))
                        .append(":end=")
                        .append(formatTime(end));
            } else {
                filter.append("trim=start=")
                        .append(formatTime(start));
            }

            filter.append(",setpts=PTS-STARTPTS[v")
                    .append(i)
                    .append("];");

            filter.append("[asrc")
                    .append(i)
                    .append("]");

            if (end >= 0) {
                filter.append("atrim=start=")
                        .append(formatTime(start))
                        .append(":end=")
                        .append(formatTime(end));
            } else {
                filter.append("atrim=start=")
                        .append(formatTime(start));
            }

            filter.append(",asetpts=PTS-STARTPTS");

            if (enhanceAudio) {
                filter.append(",")
                        .append(getAudioEnhancementFilter());
            }

            filter.append("[a")
                    .append(i)
                    .append("];");
        }

        /*
         * Join all kept video/audio segments together.
         *
         * Each video segment is paired with its matching
         * audio segment.
         */
        for (int i = 0; i < segmentCount; i++) {
            filter.append("[v")
                    .append(i)
                    .append("][a")
                    .append(i)
                    .append("]");
        }

        filter.append("concat=n=")
                .append(segmentCount)
                .append(":v=1:a=1[vout][aout]");

        return filter.toString();
    }

    private String formatTime(double time) {
        return String.format(
                Locale.US,
                "%.3f",
                time
        );
    }

    private String buildSilenceExpression(
            List<SilenceSegment> silenceSegments) {

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
            case "veryhigh" -> "-20dB";
            case "extreme" -> "-18dB";
            default -> "-25dB";
        };
    }

    private String getAudioEnhancementFilter() {

        return String.join(",",
                "highpass=f=80",
                "lowpass=f=12000",
                "afftdn=nf=-25",
                "dynaudnorm",
                "acompressor=threshold=0.089:ratio=3:attack=20:release=250",
                "loudnorm=I=-16:TP=-1.5:LRA=11"
        );
    }

    private Double extractValue(
            String line,
            String key) {

        int startIndex = line.indexOf(key);

        if (startIndex == -1) {
            return null;
        }

        String valuePart =
                line.substring(
                        startIndex + key.length()
                ).trim();

        String number =
                valuePart.split("\\s+")[0];

        return Double.parseDouble(number);
    }
}