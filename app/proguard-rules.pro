# ML Kit 自带 consumer 规则，这里兜底保险
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
