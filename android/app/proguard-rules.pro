# libwebrtc invokes Java methods by JNI name; this AAR does not ship consumer rules.
-keep class org.webrtc.** { *; }
