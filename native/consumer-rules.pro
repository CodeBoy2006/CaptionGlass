# sherpa JNI looks up configuration fields and constructs result objects by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# translator.cpp throws these exceptions by their JVM names and String constructors.
-keep class com.captionglass.nativebridge.Translation*Exception {
    public <init>(java.lang.String);
}

# translator.cpp invokes the erased Function1 callback through JNI.
-keepclassmembers class * implements kotlin.jvm.functions.Function1 {
    public java.lang.Object invoke(java.lang.Object);
}
