# CV and Portfolio Builder — Offline Android

This Android Studio project packages the CV and Portfolio Builder as a fully offline app and adds native PDF creation and printing.

## Build

1. Install Android Studio.
2. Open the `Pathfolio_Android_Native` folder.
3. Let Gradle Sync finish and install Android SDK 35 if prompted.
4. Connect an Android phone with USB debugging enabled.
5. Choose **Run > Run 'app'** for testing.
6. To create an APK, choose **Build > Build App Bundles or APKs > Build APKs**.

The debug APK will be written under `app/build/outputs/apk/debug/`.

## PDF behavior

- **Download PDF** creates an A4 PDF locally and opens Android's safe document picker.
- The suggested filename is `Full_Name_CV_Portfolio.pdf`; without a name it is `CV_Portfolio.pdf`.
- **Print** opens Android's print service separately.
- In a desktop or mobile browser, PDF export uses the browser's built-in Save as PDF function.
- Buttons and controls are excluded from the PDF by print-specific CSS.

The web content and all required icons and styles are stored locally under `app/src/main/assets/`. No external script, font, analytics service, database or server is used.

## Offline admin panel

1. Open the app and tap **Admin login** at the bottom of the home page.
2. On first use, create an admin username and a password of at least 10 characters.
3. Manage the two home cards and the Portfolio fields, descriptions, visibility and display order.
4. Changes apply immediately on that device.
5. To publish the same settings in a new APK, tap **Download config.json**, replace `app/src/main/assets/config.json` with the downloaded file, then rebuild the APK.

The password itself is never saved. The app stores a PBKDF2-SHA256 password hash with a random salt. Because this edition has no server, it cannot provide server-level authorization; anyone who can edit and rebuild the project controls the published configuration.

Student profile photos and form information remain in local WebView storage on the student's phone. They are used to create the PDF and are not uploaded by this project.
