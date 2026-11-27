/*
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "ALooperEventLoopImplementation.h"
#include "AndroidApplication.h"
#include "HubConnection.h"
#include "JNIHelpers.h"
#include "RemoteViewAndroid.h"
#include <AK/ByteString.h>
#include <AK/Format.h>
#include <AK/JsonObject.h>
#include <AK/JsonValue.h>
#include <AK/OwnPtr.h>
#include <LibCore/EventLoop.h>
#include <LibCore/System.h>
#include <LibWebView/Utilities.h>
#include <jni.h>
#include <stdlib.h>

JavaVM* global_vm;
static OwnPtr<Core::EventLoop> s_main_event_loop;
static RefPtr<Ladybird::HubConnection> s_hub_connection;
static jobject s_java_instance;
static jmethodID s_schedule_event_loop_method;
static jmethodID s_open_url_in_new_tab_method;
static jmethodID s_set_clipboard_text_method;
static jmethodID s_clipboard_text_method;
static jmethodID s_download_changed_method;
static Hub::UIInfo s_ui_info;
static u64 s_next_settings_change_id { 1 };

// In dark mode, the engine darkens the pages that have no dark color scheme of their own, as Chrome's auto dark mode
// does. Pages that list a dark color scheme keep their own colors.
static void set_force_dark(bool enabled)
{
    JsonObject content;
    content.set("enableForceDark"sv, enabled);
    JsonObject changes;
    changes.set("content"sv, move(content));
    s_hub_connection->async_update_settings(s_next_settings_change_id++, JsonValue { changes }.serialized().to_byte_string());
}

static ByteString byte_string_from_jstring(JNIEnv* env, jstring string)
{
    if (!string)
        return {};
    char const* raw = env->GetStringUTFChars(string, nullptr);
    ByteString result { raw };
    env->ReleaseStringUTFChars(string, raw);
    return result;
}

namespace Ladybird {

void AndroidApplication::open_url_in_new_tab(String const& url, bool activate, bool is_private)
{
    JavaEnvironment env(global_vm);
    auto jurl = env.jstring_from_ak_string(url);
    env.get()->CallVoidMethod(s_java_instance, s_open_url_in_new_tab_method, jurl, activate, is_private);
    env.get()->DeleteLocalRef(jurl);
}

void AndroidApplication::set_clipboard_text(String const& text)
{
    JavaEnvironment env(global_vm);
    auto jtext = env.jstring_from_ak_string(text);
    env.get()->CallVoidMethod(s_java_instance, s_set_clipboard_text_method, jtext);
    env.get()->DeleteLocalRef(jtext);
}

String AndroidApplication::clipboard_text()
{
    JavaEnvironment env(global_vm);
    auto jtext = static_cast<jstring>(env.get()->CallObjectMethod(s_java_instance, s_clipboard_text_method));
    if (!jtext)
        return {};
    auto text = byte_string_from_jstring(env.get(), jtext);
    env.get()->DeleteLocalRef(jtext);
    return String::from_utf8_with_replacement_character(text);
}

// Download states, mirrored in LadybirdActivity.kt.
void AndroidApplication::download_changed(Hub::DownloadState const& download)
{
    JavaEnvironment env(global_vm);
    auto jurl = env.jstring_from_ak_string(download.url.to_string());
    auto jdestination = env.jstring_from_ak_string(String::from_utf8_with_replacement_character(download.destination));
    env.get()->CallVoidMethod(s_java_instance, s_download_changed_method,
        static_cast<jlong>(download.id), jurl, jdestination, static_cast<jint>(download.status),
        static_cast<jlong>(download.downloaded_size), static_cast<jlong>(download.total_size.value_or(0)),
        download.is_private == WebView::IsPrivate::Yes);
    env.get()->DeleteLocalRef(jurl);
    env.get()->DeleteLocalRef(jdestination);
}

}

extern "C" JNIEXPORT jboolean JNICALL Java_org_serenityos_ladybird_LadybirdActivity_initNativeCode(JNIEnv*, jobject, jstring, jstring, jstring, jstring, jstring, jstring, jstring, jobject, jboolean);
extern "C" JNIEXPORT void JNICALL Java_org_serenityos_ladybird_LadybirdActivity_execMainEventLoop(JNIEnv*, jobject);
extern "C" JNIEXPORT void JNICALL Java_org_serenityos_ladybird_LadybirdActivity_disposeNativeCode(JNIEnv*, jobject);
extern "C" JNIEXPORT void JNICALL Java_org_serenityos_ladybird_LadybirdActivity_setDarkMode(JNIEnv*, jobject, jboolean);

extern "C" JNIEXPORT jboolean JNICALL
Java_org_serenityos_ladybird_LadybirdActivity_initNativeCode(JNIEnv* env, jobject thiz, jstring native_library_dir, jstring resource_dir, jstring data_dir, jstring cache_dir, jstring downloads_dir, jstring certificates_path, jstring tag_name, jobject timer_service, jboolean dark_mode)
{
    auto native_library_directory = byte_string_from_jstring(env, native_library_dir);
    auto resource_directory = byte_string_from_jstring(env, resource_dir);
    auto data_directory = byte_string_from_jstring(env, data_dir);
    auto cache_directory = byte_string_from_jstring(env, cache_dir);

    AK::set_log_tag_name(byte_string_from_jstring(env, tag_name).characters());

    // Every helper process inherits these. An app has no home directory of its own, so the XDG directories point into
    // the app's data and cache directories, and the helpers find their libraries next to them in the APK.
    setenv("LADYBIRD_RESOURCE_ROOT", resource_directory.characters(), 1);
    setenv("HOME", data_directory.characters(), 1);
    setenv("XDG_CONFIG_HOME", ByteString::formatted("{}/config", data_directory).characters(), 1);
    setenv("XDG_DATA_HOME", ByteString::formatted("{}/data", data_directory).characters(), 1);
    setenv("XDG_STATE_HOME", ByteString::formatted("{}/state", data_directory).characters(), 1);
    setenv("XDG_CACHE_HOME", cache_directory.characters(), 1);
    setenv("XDG_RUNTIME_DIR", ByteString::formatted("{}/runtime", cache_directory).characters(), 1);
    setenv("TMPDIR", cache_directory.characters(), 1);
    setenv("XDG_DOWNLOAD_DIR", byte_string_from_jstring(env, downloads_dir).characters(), 1);
    setenv("LD_LIBRARY_PATH", native_library_directory.characters(), 1);
    (void)Core::System::mkdir(ByteString::formatted("{}/runtime", cache_directory), 0700);

    env->GetJavaVM(&global_vm);
    VERIFY(global_vm);

    s_java_instance = env->NewGlobalRef(thiz);
    jclass clazz = env->GetObjectClass(s_java_instance);
    VERIFY(clazz);
    s_schedule_event_loop_method = env->GetMethodID(clazz, "scheduleEventLoop", "()V");
    s_open_url_in_new_tab_method = env->GetMethodID(clazz, "openUrlInNewTabFromHub", "(Ljava/lang/String;ZZ)V");
    s_set_clipboard_text_method = env->GetMethodID(clazz, "setClipboardText", "(Ljava/lang/String;)V");
    s_clipboard_text_method = env->GetMethodID(clazz, "clipboardText", "()Ljava/lang/String;");
    s_download_changed_method = env->GetMethodID(clazz, "onDownloadChanged", "(JLjava/lang/String;Ljava/lang/String;IJJZ)V");
    VERIFY(s_schedule_event_loop_method && s_open_url_in_new_tab_method && s_set_clipboard_text_method && s_clipboard_text_method && s_download_changed_method);
    env->DeleteLocalRef(clazz);

    auto* event_loop_manager = new Ladybird::ALooperEventLoopManager(env->NewGlobalRef(timer_service));
    event_loop_manager->on_did_post_event = [] {
        Ladybird::JavaEnvironment env(global_vm);
        env.get()->CallVoidMethod(s_java_instance, s_schedule_event_loop_method);
    };
    Core::EventLoopManager::install(*event_loop_manager);
    s_main_event_loop = make<Core::EventLoop>();

    // The app runs as app_process, so the Ladybird binaries are found from the APK's native library directory instead.
    WebView::platform_init(native_library_directory);

    s_ui_info = {};
    s_ui_info.interface_color_scheme = dark_mode ? Web::CSS::PreferredColorScheme::Dark : Web::CSS::PreferredColorScheme::Light;
    s_ui_info.supports_private_browsing_windows = true;
    s_ui_info.reports_scroll_momentum = true;
    s_ui_info.preferred_languages = { "en"_string };
    Ladybird::RemoteViewAndroid::set_websites_prefer_dark(dark_mode);

    Vector<ByteString> hub_arguments;
    if (auto certificates = byte_string_from_jstring(env, certificates_path); !certificates.is_empty())
        hub_arguments.append(ByteString::formatted("--certificate={}", certificates));

    auto connection = Ladybird::HubConnection::launch(native_library_directory, s_ui_info, move(hub_arguments));
    if (connection.is_error()) {
        warnln("Unable to start the Hub: {}", connection.error());
        return false;
    }
    s_hub_connection = connection.release_value();
    set_force_dark(dark_mode);
    return true;
}

extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_LadybirdActivity_execMainEventLoop(JNIEnv*, jobject)
{
    if (s_main_event_loop)
        s_main_event_loop->pump(Core::EventLoop::WaitMode::PollForEvents);
}

extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_LadybirdActivity_disposeNativeCode(JNIEnv* env, jobject)
{
    s_hub_connection = nullptr;
    s_main_event_loop = nullptr;
    s_schedule_event_loop_method = nullptr;
    env->DeleteGlobalRef(s_java_instance);
    delete &Core::EventLoopManager::the();
}

// Pages follow the app's dark mode, as do the browser's own pages.
extern "C" JNIEXPORT void JNICALL
Java_org_serenityos_ladybird_LadybirdActivity_setDarkMode(JNIEnv*, jobject, jboolean dark_mode)
{
    if (!s_hub_connection)
        return;
    auto color_scheme = dark_mode ? Web::CSS::PreferredColorScheme::Dark : Web::CSS::PreferredColorScheme::Light;
    if (s_ui_info.interface_color_scheme != color_scheme) {
        s_ui_info.interface_color_scheme = color_scheme;
        s_hub_connection->async_ui_info_changed(s_ui_info);
        set_force_dark(dark_mode);
    }
    Ladybird::RemoteViewAndroid::set_websites_prefer_dark(dark_mode);
}
