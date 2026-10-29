# Tam Decorators Scanner

A native Android app (Kotlin) for scanning receipts and invoices, built for my dad's
painting and decorating business. He was recording every receipt by hand in a logbook;
this app lets him photograph a receipt, reads the key details, and saves it as a PDF.

## How it works

```
Camera frame → find the document's 4 corners → auto-capture when steady
   → straighten (perspective crop) → filter → read text (OCR)
   → pull out date / total / VAT / invoice no. → review & edit → save (Room) → PDF / WhatsApp
```

| Step | Where | How |
|---|---|---|
| Live corner detection | `ui/ScanCameraActivity.kt`, `util/ScanProcessor.kt` | CameraX analyses a small greyscale frame; OpenCV blurs it, finds edges (Canny) and keeps the largest 4-cornered outline. |
| Auto-capture | `ui/ScanCameraActivity.kt` | Takes the photo when the outline covers enough of the screen and stays still for 12 frames. |
| Straighten & filter | `util/ScanProcessor.kt` | Perspective transform to a flat rectangle; B&W (adaptive threshold), colour boost (CLAHE), greyscale or original photo. |
| Read text | `ui/EditScanActivity.kt` | Google ML Kit text recognition, on-device (works offline). |
| Extract fields | `util/InvoiceFieldExtractor.kt` | Regular-expression rules for date, total, VAT, invoice number and customer. Supports € and £. Results are editable. |
| Save | `data/` | Room (SQLite) database for scan details; image and PDF files in app storage. |
| Export | `util/PdfExporter.kt` | A4 PDF with the Tam Decorators logo; share to WhatsApp or any app. |

## Tech

Kotlin · CameraX · OpenCV · ML Kit Text Recognition · Room · Coroutines · View Binding · JUnit

## Run

1. Open this folder in Android Studio.
2. Run the `app` configuration on a real phone (the emulator camera isn't good enough for edge detection).

Unit tests (field extraction, no phone needed):

```
gradlew test
```

## Status

Work in progress.

- Builds successfully; field-extraction rules are covered by unit tests.
- Fixed recently: camera frames with row padding were being scrambled before edge detection,
  and crop corners from the small analysis frame weren't scaled to the full-size photo.
  These fixes still need testing on a real phone.
- Next: open and delete saved scans, let the user drag the corners when detection misses,
  and group the history by date.
