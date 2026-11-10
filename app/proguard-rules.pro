# sherpa-onnx reads and writes these classes' fields from native code by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# commons-compress refers to optional codecs (xz, zstd, brotli, ...) that are not bundled.
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
