# 📄 Paper Generator — RSSB Exam Question Paper Automation

> Automated bilingual (English + Hindi) question paper generation for RSSB competitive exams,
> powered by Google Gemini AI, Apache PDFBox, and Spring Boot scheduling.

---

## 📋 Table of Contents

1. [Project Overview](#project-overview)
2. [Features](#features)
3. [BDD Scenarios](#bdd-scenarios)
4. [Project Structure](#project-structure)
5. [Setup & Configuration](#setup--configuration)
6. [API Usage](#api-usage)
7. [Scheduler Details](#scheduler-details)
8. [Gemini API Usage](#gemini-api-usage)
9. [Uniqueness Logic](#uniqueness-logic)
10. [PDF Output Format](#pdf-output-format)
11. [Logging](#logging)
12. [Tech Stack](#tech-stack)

---

## Project Overview

**paper-generator** is a Spring Boot application that:

- Accepts an **RSSB exam notification PDF** via REST API
- Extracts syllabus, exam pattern, and keywords from the PDF text
- Builds a structured bilingual prompt and sends it to **Google Gemini AI**
- Parses the AI response into **4 question paper sets** (Junior + Senior levels)
- Validates uniqueness against all previously generated questions
- Saves each set as a formatted **PDF file** using Apache PDFBox
- Runs **automatically every day at 08:00 AM** for a 90-day window via Spring Scheduler

---

## Features

| Feature | Description |
|---------|-------------|
| 📤 PDF Upload | Upload exam notification PDF; text extracted via PDFBox |
| 🤖 AI Generation | Gemini API generates 100 bilingual MCQs × 4 sets |
| 🔁 Retry Logic | Up to 3 retries on Gemini failures (exponential back-off) |
| 🔍 Uniqueness Check | No question is repeated across any run (JSON history file) |
| 📄 PDF Export | Per-set PDF with title, instructions, Section A/B/C, answer key |
| ⏰ Scheduler | Cron-based daily generation with execution log |
| 📊 Audit Logging | Separate log files for API, generation events, errors |

---

## BDD Scenarios

### Feature 1: Upload Exam Notification

```gherkin
Feature: Upload exam notification PDF

  Background:
    Given the application is running on port 8080
    And no notification has been uploaded yet

  Scenario: Successfully upload a valid PDF
    Given I have a valid RSSB notification PDF file
    When I POST the file to "/api/uploadNotification"
    Then the response status should be 200
    And the response body should contain "success: true"
    And NotificationData should be stored in memory with:
      | field       | value                    |
      | title       | extracted from PDF       |
      | syllabus    | extracted from PDF       |
      | examPattern | extracted from PDF       |
      | keywords    | tokenised from syllabus  |

  Scenario: Reject a non-PDF file
    Given I have a file with content type "text/plain"
    When I POST the file to "/api/uploadNotification"
    Then the response status should be 400
    And the response body should contain "Only PDF files are accepted"

  Scenario: Reject an empty file
    Given I submit an empty multipart request
    When I POST to "/api/uploadNotification"
    Then the response status should be 400
    And the response body should contain "No file provided"

  Scenario: Retrieve stored notification
    Given a PDF has already been uploaded
    When I GET "/api/notification"
    Then the response status should be 200
    And the response should include title, syllabus, examPattern, keywords

  Scenario: Clear stored notification
    Given a notification is currently stored
    When I DELETE "/api/notification"
    Then the response status should be 200
    And subsequent GET "/api/notification" should return "No notification uploaded"
```

---

### Feature 2: Gemini API Integration

```gherkin
Feature: Generate questions via Gemini AI

  Background:
    Given a valid NotificationData is stored in memory
    And gemini.api.key and gemini.api.url are set in config.properties

  Scenario: Successfully generate questions
    Given the Gemini API is reachable
    When generateQuestions(prompt) is called
    Then a non-empty response string should be returned
    And the response should contain "=== SET 1 ===" through "=== SET 4 ==="
    And the response should contain "=== ANSWER KEY ==="

  Scenario: Retry on Gemini server error (5xx)
    Given the Gemini API returns HTTP 503 on the first call
    When generateQuestions(prompt) is called
    Then the service should retry after 2 seconds
    And retry again after 4 seconds if still failing
    And return empty string after 3 failed attempts

  Scenario: No retry on Gemini client error (4xx)
    Given the Gemini API returns HTTP 401 Unauthorized
    When generateQuestions(prompt) is called
    Then a GeminiApiException should be thrown immediately
    And no retry should occur

  Scenario: Prompt contains uniqueness seed
    When buildPrompt(notificationData) is called
    Then the prompt should contain today's date
    And the prompt should contain a UUID seed
    And the prompt should contain "Do NOT repeat any previous questions"
    And each call to buildPrompt should produce a different UUID seed
```

---

### Feature 3: Question Paper Generation

```gherkin
Feature: Generate daily question papers

  Background:
    Given a valid NotificationData is in memory
    And the Gemini API is configured and reachable

  Scenario: Successful generation on first attempt
    Given the Gemini API returns 4 valid question sets
    And none of the generated questions exist in previous_questions.json
    When generateDailyPapers() is called
    Then 4 QuestionPaper objects should be returned
    And each set should contain 100 questions
    And each question should have EN or HIN language tag
    And each paper should have a populated answerKey map
    And all questions should be saved to previous_questions.json

  Scenario: Retry when duplicates are detected
    Given the Gemini API returns questions that already exist in history
    When generateDailyPapers() is called
    Then the service should retry with a new UUID seed
    And up to 3 retry attempts should be made
    And if all attempts produce duplicates, return an empty list

  Scenario: No notification data available
    Given no PDF has been uploaded
    When generateDailyPapers() is called
    Then an empty list should be returned
    And a log message "No NotificationData available" should be written

  Scenario: Gemini returns empty response
    Given the Gemini API returns an empty string
    When generateDailyPapers() is called
    Then the attempt should be retried
    And if all 3 attempts return empty, return an empty list
```

---

### Feature 4: Uniqueness Validation

```gherkin
Feature: Validate uniqueness of generated questions

  Background:
    Given previous_questions.json exists in the output directory

  Scenario: All questions are new
    Given previous_questions.json contains 300 questions
    And the new batch contains 400 different questions
    When isUnique(newQuestions) is called
    Then the result should be true
    And a log message "Uniqueness check passed for 400 questions" should appear

  Scenario: Duplicate question detected
    Given previous_questions.json contains question "What is Ohm's Law?"
    And the new batch also contains "What is Ohm's Law?"
    When isUnique(newQuestions) is called
    Then the result should be false
    And a log warning "Duplicate question detected" should appear

  Scenario: Case-insensitive duplicate detection
    Given history contains "what is ohm's law?"
    And new batch contains "WHAT IS OHM'S LAW?"
    When isUnique(newQuestions) is called
    Then the result should be false
    (because normalisation lowercases both before comparison)

  Scenario: Whitespace-normalised duplicate detection
    Given history contains "What  is  Rajasthan?"
    And new batch contains "What is Rajasthan?"
    When isUnique(newQuestions) is called
    Then the result should be false
    (because normalisation collapses internal spaces)

  Scenario: Save new questions to history
    Given isUnique returns true for 400 new questions
    When saveQuestions(newQuestions) is called
    Then previous_questions.json should be updated
    And it should contain all existing + new questions
    And no duplicates should exist in the file
```

---

### Feature 5: PDF Generation

```gherkin
Feature: Generate PDF question paper

  Background:
    Given a QuestionPaper object with setNumber=1, category=JUNIOR
    And the paper contains 100 questions (50 EN + 50 HIN each level)
    And answerKey map is populated with Q1–Q100

  Scenario: Successfully generate PDF
    When generatePdf(paper) is called
    Then a file should be created at output.dir/Set1_Junior_<today>.pdf
    And the file should be a valid PDF
    And the PDF should contain the RSSB header
    And the PDF should contain General Instructions
    And the PDF should contain Section A (Junior English questions)
    And the PDF should contain Section B (Senior English questions)
    And the PDF should contain Section C (Hindi reference questions)
    And the PDF should contain the Answer Key

  Scenario: PDF filename includes set number, category, and date
    Given a paper with setNumber=3 and category=SENIOR
    When generatePdf(paper) is called
    Then the filename should be "Set3_Senior_31-05-2026.pdf"

  Scenario: Hindi font available
    Given "NotoSansDevanagari-Regular.ttf" exists in resources/fonts/
    When a PDF is generated
    Then Devanagari characters should render correctly in Section C

  Scenario: Hindi font not available (fallback)
    Given no TTF font exists in resources/fonts/
    When a PDF is generated
    Then the PDF should still be generated successfully
    And a warning "Hindi font not found" should be logged
    And Hindi text will use a fallback Latin font
```

---

### Feature 6: Scheduler

```gherkin
Feature: Scheduled daily paper generation

  Background:
    Given the application is running
    And schedule.cron=0 0 8 * * * in config.properties
    And duration.days=90 in config.properties

  Scenario: First execution of the day
    Given today's date is NOT in executed-dates.log
    And the 90-day window has not been exceeded
    When the cron trigger fires at 08:00 AM
    Then generateDailyPapers() should be called
    And if papers are generated successfully
    Then today's date should be appended to executed-dates.log

  Scenario: Already executed today
    Given today's date IS already in executed-dates.log
    When the cron trigger fires (e.g. after application restart)
    Then generation should be SKIPPED
    And log message "Date already in execution log" should appear

  Scenario: 90-day window exceeded
    Given 90+ days have passed since the first recorded execution
    When the cron trigger fires
    Then generation should be SKIPPED
    And log warning "Execution window exceeded" should appear

  Scenario: Generation fails — date not recorded
    Given the Gemini API is unavailable
    When the cron fires and generateDailyPapers() returns empty
    Then today's date should NOT be added to executed-dates.log
    And the next trigger attempt should retry generation

  Scenario: Immediate manual generation via API
    When I GET "/api/scheduler/generateNow"
    Then generateDailyPapers() is called immediately regardless of schedule
    And today's date is marked as executed on success
    And the response includes sets generated, question count, answer key entries
```

---

## Project Structure

```
paper-generator/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/uttam/paper/
    │   │   ├── PaperGeneratorApplication.java
    │   │   ├── aspect/
    │   │   │   └── LoggingAspect.java          # AOP logging
    │   │   ├── config/
    │   │   │   ├── ConfigLoader.java           # @ConfigurationProperties
    │   │   │   └── RestTemplateConfig.java     # RestTemplate + ObjectMapper beans
    │   │   ├── controller/
    │   │   │   ├── NotificationController.java # POST /uploadNotification
    │   │   │   └── SchedulerController.java    # GET /generateNow, status, trigger
    │   │   ├── dto/
    │   │   │   ├── ApiResponse.java            # Generic response wrapper
    │   │   │   └── gemini/
    │   │   │       ├── GeminiRequest.java      # Gemini API request body
    │   │   │       └── GeminiResponse.java     # Gemini API response parser
    │   │   ├── model/
    │   │   │   ├── Category.java               # JUNIOR / SENIOR
    │   │   │   ├── Language.java               # EN / HIN
    │   │   │   ├── NotificationData.java       # Parsed notification
    │   │   │   ├── Question.java               # Single MCQ
    │   │   │   └── QuestionPaper.java          # Full set with answer key
    │   │   ├── service/
    │   │   │   ├── GeminiService.java          # Gemini API client + retry
    │   │   │   ├── NotificationService.java    # PDF text extraction + storage
    │   │   │   ├── PdfService.java             # PDFBox PDF generation
    │   │   │   ├── QuestionHistoryService.java # JSON history persistence
    │   │   │   ├── QuestionPaperService.java   # Orchestration pipeline
    │   │   │   └── SchedulerService.java       # Cron job + execution log
    │   │   └── util/
    │   │       ├── PromptBuilder.java          # Gemini prompt construction
    │   │       └── QuestionParser.java         # Raw text → QuestionPaper
    │   └── resources/
    │       ├── application.properties
    │       ├── config.properties               # API keys, cron, output dir
    │       ├── logback-spring.xml              # Logging configuration
    │       └── fonts/
    │           └── NotoSansDevanagari-Regular.ttf  # (place manually)
    └── test/
        └── java/com/uttam/paper/
            └── PaperGeneratorApplicationTests.java
```

---

## Setup & Configuration

### Prerequisites

| Requirement | Version |
|-------------|---------|
| Java        | 17+     |
| Maven       | 3.8+    |
| Gemini API Key | [Get here](https://makersuite.google.com/app/apikey) |

### Step 1: Clone and build

```bash
cd Documents/RSSBAutomation/paper-generator
mvn clean install -DskipTests
```

### Step 2: Configure `src/main/resources/config.properties`

```properties
# Required: your Google Gemini API key
gemini.api.key=YOUR_GEMINI_API_KEY_HERE

# Gemini API endpoint
gemini.api.url=https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent

# Directory where PDFs and history files are saved
output.dir=output/papers

# Cron schedule (default: daily at 08:00 AM)
schedule.cron=0 0 8 * * *

# Number of days to run the scheduler
duration.days=90
```

### Step 3: (Optional) Add Hindi font

Download [Noto Sans Devanagari](https://fonts.google.com/noto/specimen/Noto+Sans+Devanagari)
and place the file at:
```
src/main/resources/fonts/NotoSansDevanagari-Regular.ttf
```

### Step 4: Run the application

```bash
mvn spring-boot:run
```

The application starts on **http://localhost:8080**

---

## API Usage

### Upload Notification PDF

```http
POST /api/uploadNotification
Content-Type: multipart/form-data

Form field: file = <your-notification.pdf>
```

**Success Response:**
```json
{
  "success": true,
  "message": "Notification uploaded and parsed successfully.",
  "data": {
    "title": "RSSB Junior Engineer Recruitment 2024",
    "syllabus": "General Science, Mathematics, Reasoning...",
    "examPattern": "100 MCQs, 2 hours, 1/3 negative marking",
    "keywords": ["mathematics", "reasoning", "science", "rajasthan"]
  }
}
```

---

### Get Stored Notification

```http
GET /api/notification
```

---

### Clear Notification

```http
DELETE /api/notification
```

---

### Generate Papers Immediately

```http
GET /api/scheduler/generateNow
```

**Success Response:**
```json
{
  "success": true,
  "message": "Papers and PDFs generated successfully for 2026-05-31.",
  "data": {
    "date": "2026-05-31",
    "setsGenerated": 4,
    "totalQuestions": 400,
    "answerKeys": {
      "Set1": "100 entries",
      "Set2": "100 entries",
      "Set3": "100 entries",
      "Set4": "100 entries"
    }
  }
}
```

---

### Scheduler Status

```http
GET /api/scheduler/status
```

**Response:**
```json
{
  "success": true,
  "message": "Scheduler status.",
  "data": {
    "executedDates": ["2026-05-29", "2026-05-30", "2026-05-31"],
    "totalExecuted": 3,
    "daysRemaining": 87
  }
}
```

---

### Manual Trigger for Specific Date

```http
POST /api/scheduler/trigger?date=2026-06-01&force=false
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `date`    | today   | Target date (ISO format: yyyy-MM-dd) |
| `force`   | false   | If true, bypasses already-executed guard |

---

## Scheduler Details

### Cron Expression

The cron expression is configurable via `config.properties`:

```properties
# Default: every day at 08:00:00 AM
schedule.cron=0 0 8 * * *
```

| Field    | Value | Meaning        |
|----------|-------|----------------|
| Second   | 0     | At second 0    |
| Minute   | 0     | At minute 0    |
| Hour     | 8     | At 8 AM        |
| Day      | *     | Every day      |
| Month    | *     | Every month    |
| Weekday  | *     | Every weekday  |

### Execution Window

- Scheduler runs for `duration.days` (default: **90 days**) from the **first** recorded execution
- After 90 days, the job silently skips with a log warning

### Execution Log (`executed-dates.log`)

```
2026-05-31
2026-06-01
2026-06-02
...
```

- Located in `output.dir` (configurable)
- Append-only — dates are never removed
- If today's date is present, the job is **skipped** (idempotent)
- If generation **fails**, the date is **not** recorded (allows retry)

---

## Gemini API Usage

### Endpoint

```
POST https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent?key=API_KEY
```

### Request Body

```json
{
  "contents": [
    {
      "parts": [
        { "text": "<full prompt with syllabus, format instructions, seed>" }
      ]
    }
  ],
  "generationConfig": {
    "temperature": 0.7,
    "maxOutputTokens": 2048,
    "topP": 0.95
  }
}
```

### Prompt Structure

```
=== SESSION METADATA ===
Generation Date : 31-05-2026
Unique Seed     : 550e8400-e29b-41d4-a716-446655440000

LANGUAGE INSTRUCTION:
  Every question must be in BOTH English [EN] and Hindi [HIN]

EXAM CONTEXT:
  Title / Syllabus / Exam Pattern from NotificationData

EXAM FORMAT (RSSB 2022):
  100 MCQs, 4 options, -1/3 negative marking, 2 hours

SET INSTRUCTION:
  Generate 4 complete sets (SET 1–4), 100 unique questions each

DIFFICULTY LEVEL:
  50 JUNIOR questions + 50 SENIOR questions per set

TOPIC COVERAGE:
  keywords extracted from syllabus

OUTPUT FORMAT:
  === SET N === headers, Q1–Q100, === ANSWER KEY ===

UNIQUENESS REQUIREMENT (MANDATORY):
  Do NOT repeat any previous questions from any prior session
```

### Retry Policy

| Scenario | Behaviour |
|----------|-----------|
| `5xx` Server Error | Retry after 2s → 4s → 8s (max 3 attempts) |
| Network timeout | Same exponential back-off |
| `4xx` Client Error | Throw `GeminiApiException` immediately (no retry) |
| All retries exhausted | `@Recover` returns empty string `""` |

---

## Uniqueness Logic

Every generated question goes through a 3-layer uniqueness system:

### Layer 1: Prompt Seed (Prevent AI repetition)

Each call to `PromptBuilder.build()` embeds:
- **Today's date** — consistent per day
- **New UUID** — unique per attempt

```
Generation Date : 31-05-2026
Unique Seed     : f47ac10b-58cc-4372-a567-0e02b2c3d479
```

Instruction appended to every prompt:
> *"Do NOT repeat any previous questions from any prior session or generation."*

---

### Layer 2: Cross-Attempt Retry (In-Process)

`QuestionPaperService.generateDailyPapers()` retries up to **3 times** with a new seed if `isUnique()` returns `false`.

```
Attempt 1 → seed=UUID-1 → duplicate? → retry
Attempt 2 → seed=UUID-2 → duplicate? → retry
Attempt 3 → seed=UUID-3 → duplicate? → return []
```

---

### Layer 3: History File (Cross-Run)

All accepted questions are saved to `previous_questions.json`:

```json
[
  "what is the capital of rajasthan?",
  "राजस्थान की राजधानी क्या है?",
  ...
]
```

**Normalisation before comparison:**
```
"  What is Ohm's  Law?  "
        ↓ trim
"What is Ohm's  Law?"
        ↓ collapse spaces
"What is Ohm's Law?"
        ↓ lowercase
"what is ohm's law?"
```

This means duplicates are caught regardless of:
- Leading/trailing whitespace
- Internal extra spaces
- Letter case differences

---

## PDF Output Format

Each generated set is saved as:

```
output/papers/Set1_Junior_31-05-2026.pdf
output/papers/Set2_Junior_31-05-2026.pdf
output/papers/Set3_Senior_31-05-2026.pdf
output/papers/Set4_Senior_31-05-2026.pdf
```

### PDF Internal Structure

```
┌──────────────────────────────────────────────┐
│  RAJASTHAN STAFF SELECTION BOARD (RSSB)      │
│  COMPETITIVE EXAMINATION — QUESTION PAPER    │
│  SET 1  |  JUNIOR LEVEL  |  31 May 2026      │
│  Questions: 100  Marks: 100  Time: 2 Hours   │
├──────────────────────────────────────────────┤
│  GENERAL INSTRUCTIONS (10 points)            │
├──────────────────────────────────────────────┤
│  SECTION A — JUNIOR LEVEL (English)          │
│  Q1. <question>                              │
│  Q2. <question> ...                          │
├──────────────────────────────────────────────┤
│  SECTION B — SENIOR LEVEL (English)          │
│  Q51. <question> ...                         │
├──────────────────────────────────────────────┤
│  SECTION C — सभी प्रश्न हिंदी में            │
│  Q1. <हिंदी प्रश्न> ...                      │
├──────────────────────────────────────────────┤
│  ANSWER KEY                                  │
│  Q1(A)  Q2(C)  Q3(B) ... (10 per row)        │
└──────────────────────────────────────────────┘
```

---

## Logging

### Log Files

| File | Contents |
|------|----------|
| `logs/paper-generator.log` | All application logs (INFO+) |
| `logs/paper-generator-error.log` | Errors only |
| `logs/paper-generator-api.log` | REST API call audit trail |
| `logs/paper-generator-generation.log` | Generation events, retries, duplicates |
| `logs/archive/` | Daily rotated + gzipped archives (90-day retention) |

### Key Log Events

```
→ API CALL  [NotificationController.uploadNotification]  args=[file(notification.pdf)]
← API OK    [NotificationController.uploadNotification]  duration=342ms

┌─ GEMINI REQUEST  promptLength=3842 chars
└─ GEMINI RESPONSE  responseLength=28400 chars  duration=4190ms

┌─ UNIQUENESS CHECK  checking=400 questions
└─ UNIQUENESS PASS  ✓ all 400 questions are new  duration=12ms

╔══════════════════════════════════════════
║  PAPER GENERATION SUCCESS ✓
║  Sets      : 4
║  Questions : 400
║  Duration  : 4820ms
╚══════════════════════════════════════════

◀ PDF SAVED  ✓  path=output/papers/Set1_Junior_31-05-2026.pdf  duration=230ms
```

---

## Tech Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| Java | 17 | Language |
| Spring Boot | 3.2.5 | Framework |
| Spring Web | — | REST API |
| Spring Scheduling | — | Cron job |
| Spring Retry | — | Gemini retry logic |
| Spring AOP | — | Logging aspect |
| Spring Validation | — | Config validation |
| Apache PDFBox | 3.0.2 | PDF read & write |
| Jackson | 2.x | JSON serialisation |
| Lombok | — | Boilerplate reduction |
| SLF4J + Logback | — | Logging |
| Maven | 3.8+ | Build tool |

---

## License

This project is for internal RSSB automation use.
