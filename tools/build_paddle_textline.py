"""Rebuild the bundled upstream PaddleOCR text-line SDK classes with Gradle 8.13's Kotlin compiler."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
from urllib.request import urlopen
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

REVISION = "dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf"
UPSTREAM = ("https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/" + REVISION
            + "/deploy/ppocr-android/ppocr-sdk/src/main/java/com/paddle/ocr/")
FILES = (
    "model/OCRBox.kt", "util/MathUtils.kt", "util/ImageUtils.kt", "util/BitmapUtils.kt",
    "preprocess/DetPreprocessor.kt", "preprocess/RecPreprocessor.kt", "postprocess/DBPostProcessor.kt",
    "postprocess/PolygonUnclip.kt", "postprocess/QuadGeometry.kt", "postprocess/QuadTextCrop.kt",
    "postprocess/BoxSorter.kt", "postprocess/CTCDecoder.kt",
)

def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("--gradle", type=Path, required=True, help="unpacked Gradle 8.13 distribution")
    args.add_argument("--android-jar", type=Path, required=True, help="Android 36 android.jar")
    args.add_argument("--opencv-aar", type=Path, required=True, help="org.opencv:opencv:4.11.0")
    args.add_argument("--source", type=Path, help="optional upstream com/paddle/ocr directory at the pinned revision")
    opts = args.parse_args()
    lib = opts.gradle.resolve() / "lib"
    java = Path(os.environ["JAVA_HOME"]) / "bin/java" if "JAVA_HOME" in os.environ else "java"
    with tempfile.TemporaryDirectory() as work:
        root = Path(work)
        with ZipFile(opts.opencv_aar) as opencv:
            classes = root / "opencv.jar"
            classes.write_bytes(opencv.read("classes.jar"))
        src = root / "src"
        paths = []
        for name in FILES:
            target = src / name
            target.parent.mkdir(parents=True, exist_ok=True)
            if opts.source:
                target.write_bytes((opts.source / name).read_bytes())
            else:
                with urlopen(UPSTREAM + name, timeout=30) as response:
                    target.write_bytes(response.read())
            paths.append(str(target))
        out = root / "classes"
        compile_path = os.pathsep.join(str(p) for p in (opts.android_jar.resolve(), classes,
                                                        lib / "kotlin-stdlib-2.0.21.jar"))
        subprocess.run([str(java), "-cp", str(lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-jvm-target", "1.8", "-classpath", compile_path,
                        "-module-name", "paddleocr_textline", "-d", str(out), *paths], check=True)
        destination = Path(__file__).resolve().parents[1] / "app/libs/paddleocr-textline.jar"
        destination.parent.mkdir(exist_ok=True)
        with ZipFile(destination, "w") as jar:
            for file in sorted(out.rglob("*")):
                if file.is_file():
                    jar.writestr(ZipInfo(file.relative_to(out).as_posix()), file.read_bytes(),
                                 compress_type=ZIP_DEFLATED)

if __name__ == "__main__":
    main()
