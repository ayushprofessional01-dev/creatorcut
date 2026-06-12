package com.ayush.creatorcut.model;

public class SilenceSegment {

    private double start;
    private double end;
    private double duration;

    public SilenceSegment(double start, double end) {
        this.start = start;
        this.end = end;
        this.duration = end - start;
    }

    public double getStart() {
        return start;
    }

    public double getEnd() {
        return end;
    }

    public double getDuration() {
        return duration;
    }
}