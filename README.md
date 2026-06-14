# CreatorCut

CreatorCut is a Spring Boot web application that helps content creators automatically remove silent parts from uploaded videos. Users can upload a video, choose silence sensitivity settings, process the video using FFmpeg, and download the trimmed output.

## Problem It Solves

Content creators often spend a lot of time manually cutting pauses and silent parts from their recordings. CreatorCut automates this process and reduces basic editing effort.

## Features

* Upload video files through a web interface
* Detect silent parts using FFmpeg
* Remove silent segments automatically
* Download the trimmed video
* Choose silence sensitivity:

  * Low
  * Medium
  * High
* Choose minimum silence duration:

  * 0.3 seconds
  * 0.5 seconds
  * 1 second
* Clean result page showing detected silent segments

## Tech Stack

* Java
* Spring Boot
* Thymeleaf
* HTML
* CSS
* FFmpeg
* Maven

## Project Flow

```text
User uploads video
        ↓
Spring Boot saves the file
        ↓
FFmpeg detects silent segments
        ↓
FFmpeg removes silent parts
        ↓
Processed video is generated
        ↓
User downloads trimmed video
```


These folders are ignored by Git and are not pushed to GitHub.
