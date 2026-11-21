# Keep the app's package-level APIs and fields used by the acceptance runner.
# Dependencies can still be shrunk; no production model or feature is removed.
-keep class com.scandesk.app.** { *; }

# ONNX Runtime and OpenCV call Java classes and members from native code.
-keep class ai.onnxruntime.** { *; }
-keep class org.opencv.** { *; }
-keep class com.paddle.ocr.** { *; }

# Public PDF APIs are also used by the independently compiled acceptance APK.
-keep public class com.tom_roush.pdfbox.** { public *; }
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep public class androidx.camera.view.transform.** { public *; }
-keep interface androidx.camera.core.ImageProxy { *; }
-keep class androidx.core.content.FileProvider { public *; }
# The separately optimized acceptance APK drives the camera's retained review.
-keep public class androidx.lifecycle.ViewModelProvider { public *; }
-keep interface androidx.lifecycle.ViewModelStoreOwner { *; }
-keep public class androidx.lifecycle.MutableLiveData { public *; }
-keep class com.google.android.gms.tasks.Tasks { public *; }
-keep class com.google.mlkit.vision.common.InputImage { public *; }
-keep class com.google.mlkit.vision.text.Text { public *; }
-keep class com.google.mlkit.vision.text.Text$* { public *; }
-keep interface com.google.mlkit.vision.text.TextRecognizer { public *; }
-keep class com.google.mlkit.vision.text.TextRecognition { *; }
-keep class com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions { *; }
-keep class com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions$Builder { *; }
-keep class com.google.zxing.qrcode.QRCodeWriter { public *; }
-keep class com.google.zxing.common.BitMatrix { public *; }
-keep class com.google.zxing.BarcodeFormat { *; }
-keep class com.google.zxing.EncodeHintType { *; }

# PDF security providers instantiate algorithms through class names.
-keep class org.bouncycastle.** { *; }

# Optional upstream integrations already absent from the Android runtime:
# PdfBox reports an unavailable JPEG2000 codec; BC's JNDI/LDAP fetchers are
# Java desktop/network adapters, not used by this offline document app.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
-dontwarn javax.naming.NameClassPair
-dontwarn javax.naming.NamingEnumeration
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.Attribute
-dontwarn javax.naming.directory.Attributes
-dontwarn javax.naming.directory.DirContext
-dontwarn javax.naming.directory.InitialDirContext
-dontwarn javax.naming.directory.SearchControls
-dontwarn javax.naming.directory.SearchResult
