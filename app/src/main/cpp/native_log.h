#pragma once
// Non-blocking logging bridge to the in-app DebugLog.
//
// The old uiLog() did the JNI call (and often AttachCurrentThread/Detach) on
// the calling thread, frequently while holding the BASSMIDI mutex that the
// realtime audio callback also needs. A burst of "SET PRESET"/"VOICE RESOLVE"
// lines during a section change therefore stalled the callback = audible
// stutter. nativeLogPost() only formats + enqueues; a single background thread
// (attached to the JVM once) delivers to DebugLog.add().
void nativeLogPost(const char* tag, const char* msg);

// logcat only (no JNI, no queue) for very chatty diagnostics.
void nativeLogcatOnly(const char* tag, const char* msg);
