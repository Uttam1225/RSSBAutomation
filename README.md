# 📄 RSSB Exam Paper Generator

> Automated bilingual (English + Hindi) MCQ question paper generation for RSSB competitive exams,
> powered by **Google Gemini AI**, **Apache PDFBox**, and **Spring Boot**.

[![Java](https://img.shields.io/badge/Java-17-orange?logo=java)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen?logo=springboot)](https://spring.io/projects/spring-boot)
[![Gemini](https://img.shields.io/badge/Gemini-2.5%20Flash-blue?logo=google)](https://ai.google.dev/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

---

## 🧩 What It Does

1. **Upload** an RSSB exam notification PDF → app extracts syllabus & keywords
2. **Generate** 4 sets × 100 bilingual MCQ questions using Google Gemini AI
3. **Export** each set as a formatted PDF (question paper + answer key)
4. **Schedule** automatic daily generation at 08:00 AM for a 90-day window
5. **Prevent repeats** — every question is logged; no duplicate across runs

---

## ✨ Features

| Feature | Details |
|---|---|
| 📤 PDF Upload | Upload RSSB notification PDF; syllabus parsed via Apache PDFBox |
| 🤖 AI Generation | Gemini 2.5 Flash generates 100 bilingual MCQs × 4 sets per run |
| 🌐 Bilingual PDF | Questions in English with Hindi translation; segmented font rendering |
| 🔠 Unicode Fonts | NotoSans (Latin/Greek/₹) + NotoSansDevanagari (हिन्दी) |
| 🔁 Retry Logic | Up to 3 retries on Gemini failures with exponential back-off |
| 🔍 Uniqueness | History tracked in `previous_questions.json` — no repeats ever |
| ⏰ Scheduler | Cron-based daily auto-generation with execution log |
| 📊 Logging | Separate log files: API, generation events, errors |

---

## 🗂️ Project Structure

```
paper-generator/
├── src/main/java/com/uttam/paper/
│   ├── config/          # ConfigLoader, RestTemplate, RetryListener
│   ├── controller/      # NotificationController, SchedulerController
│   ├── dto/             # API response DTOs, GeminiRequest/Response
│   ├── exception/       # Custom exceptions + GlobalExceptionHandler
│   ├── model/           # NotificationData, QuestionPaper, Question
│   ├── service/         # GeminiService, PdfService, SchedulerService, …
│   └── util/            # PromptBuilder, QuestionParser
├── src/main/resources/
│   ├── fonts/
│   │   ├── NotoSans-Regular.ttf            # Latin, Greek, ₹, math symbols
│   │   └── NotoSansDevanagari-Regular.ttf  # Hindi (Devanagari) script
│   ├── application.properties
│   ├── config.properties                   # API key + config (not committed)
│   └── logback-spring.xml
├── output/papers/                          # Generated PDFs land here
├── config.properties.example              # Template — copy & fill in your key
└── pom.xml
```

---

## ⚙️ Setup

### Prerequisites

- Java 17+
- Maven 3.8+
- Google Gemini API key ([get one here](https://aistudio.google.com/app/apikey))

### 1. Clone the repo

```bash
git clone https://github.com/Uttam1225/RSSBAutomation.git
cd RSSBAutomation
```

### 2. Configure API key

```bash
cp config.properties.example src/main/resources/config.properties
```

Edit `src/main/resources/config.properties`:

```properties
gemini.api.key=YOUR_GEMINI_API_KEY_HERE
gemini.api.url=https://generativelanguage.googleapis.com/v1/models/gemini-2.5-flash:generateContent

output.dir=output/papers
schedule.cron=0 0 8 * * *
duration.days=90
```

### 3. Run

```bash
mvn spring-boot:run
```

App starts on **http://localhost:8080**

---

## 🚀 API Usage

### Upload Exam Notification PDF

```bash
curl -X POST http://localhost:8080/api/uploadNotification \
     -F "file=@/path/to/RSSB.pdf"
```

**Response:**
```json
{
  "success": true,
  "message": "Notification uploaded and parsed successfully.",
  "data": {
    "title": "...",
    "syllabus": "...",
    "keywords": ["..."]
  }
}
```

---

### Trigger Paper Generation (manual)

```bash
curl http://localhost:8080/api/scheduler/generateNow
```

> ⚠️ Generation takes **~5–10 minutes** (4 Gemini calls × ~80s each).

**Response on success:**
```json
{
  "success": true,
  "message": "4 paper set(s) generated successfully.",
  "data": ["Set1_exam_31-05-2026.pdf", "Set2_...", "Set3_...", "Set4_..."]
}
```

---

### Other Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET`  | `/api/notification` | View currently loaded notification |
| `DELETE` | `/api/notification` | Clear loaded notification |

---

## 📄 PDF Output Format

Each generated PDF contains:

- **Header** — Exam title, set number, date, total marks
- **Instructions** — 6 standard exam instructions (bilingual)
- **Questions** — Each question shown as:
  ```
  Q1. What is the capital of Rajasthan?
      राजस्थान की राजधानी क्या है?
      (a) Jaipur   (b) Jodhpur   (c) Udaipur   (d) Kota
  ```
- **Answer Key** — Grid of Q1–Q100 correct answers

---

## ⏰ Scheduler

The app auto-generates papers every day at 08:00 AM (configurable via `schedule.cron`).

- Runs for `duration.days` days (default: 90)
- Tracks execution dates in `output/papers/executed-dates.log`
- Skips if already run today

To force a re-run on the same day, clear `executed-dates.log`:
```bash
echo "" > output/papers/executed-dates.log
```

---

## 🔑 Gemini API Notes

- **Model**: `gemini-2.5-flash` via `v1` endpoint
- **Free tier**: 20 requests/day; resets at midnight Pacific Time (~12:30 PM IST)
- **Token limit**: `maxOutputTokens=65536` per request
- **Auth**: API key passed as `?key=` query parameter

---

## 📦 Tech Stack

| Technology | Version | Purpose |
|---|---|---|
| Spring Boot | 3.2.5 | Web framework, scheduler, DI |
| Apache PDFBox | 3.x | PDF parsing (input) + rendering (output) |
| Google Gemini AI | 2.5 Flash | MCQ question generation |
| Noto Fonts | Latest | Unicode + Devanagari rendering in PDFs |
| Spring Retry | 2.x | Exponential back-off on Gemini failures |
| Lombok | 1.18.x | Boilerplate reduction |
| Jackson | 2.x | JSON serialization |
| Logback | 1.4.x | Structured logging |

---

## 📁 Output

Generated PDFs are saved to `output/papers/`:

```
output/papers/
├── Set1_exam_31-05-2026.pdf
├── Set2_exam_31-05-2026.pdf
├── Set3_exam_31-05-2026.pdf
├── Set4_exam_31-05-2026.pdf
├── previous_questions.json   # All-time question history
└── executed-dates.log        # Dates when generation ran
```

---

## 🤝 Contributing

1. Fork the repo
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Commit your changes: `git commit -m "Add my feature"`
4. Push and open a Pull Request

---

## 📜 License

MIT License — see [LICENSE](LICENSE) for details.

---

*Built with ❤️ for RSSB exam preparation automation*
