package com.ayush.creatorcut.model;

import java.util.List;

public class ProcessingResult {

    private String outputFileName;
    private List<SilenceSegment> silenceSegments;

    public ProcessingResult(String outputFileName, List<SilenceSegment> silenceSegments) {
        this.outputFileName = outputFileName;
        this.silenceSegments = silenceSegments;
    }

    public String getOutputFileName() {
        return outputFileName;
    }

    public List<SilenceSegment> getSilenceSegments() {
        return silenceSegments;
    }
}