/*
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "HubConnection.h"
#include "JNIHelpers.h"
#include "RemoteViewAndroid.h"
#include <AK/AllOf.h>
#include <AK/CharacterTypes.h>
#include <LibURL/Parser.h>
#include <LibURL/PublicSuffixData.h>
#include <LibURL/URL.h>
#include <LibWebCommon/Loader/UserAgent.h>
#include <LibWebView/SearchEngine.h>
#include <LibWebView/URL.h>
#include <LibWebView/UserAgent.h>
#include <android/bitmap.h>
#include <android/native_window_jni.h>
#include <jni.h>

using namespace Ladybird;

static RemoteViewAndroid& view_from(jlong instance)
{
    return *reinterpret_cast<RemoteViewAndroid*>(instance);
}

static String string_from_jstring(JNIEnv* env, jstring string)
{
    char const* raw = env->GetStringUTFChars(string, nullptr);
    auto result = String::from_utf8_with_replacement_character(StringView { raw, strlen(raw) });
    env->ReleaseStringUTFChars(string, raw);
    return result;
}

static Optional<WebView::SearchEngine> find_search_engine(StringView name)
{
    for (auto const& engine : WebView::builtin_search_engines()) {
        if (engine.name == name)
            return engine;
    }
    return {};
}

#define LADYBIRD_JNI(return_type, name, ...)                                                                               \
    extern "C" JNIEXPORT return_type JNICALL Java_org_serenityos_ladybird_WebViewImplementation_##name(__VA_ARGS__); \
    extern "C" JNIEXPORT return_type JNICALL Java_org_serenityos_ladybird_WebViewImplementation_##name(__VA_ARGS__)

#define LADYBIRD_JNI_STATIC(return_type, name, ...)                                                                                  \
    extern "C" JNIEXPORT return_type JNICALL Java_org_serenityos_ladybird_WebViewImplementation_00024Companion_##name(__VA_ARGS__); \
    extern "C" JNIEXPORT return_type JNICALL Java_org_serenityos_ladybird_WebViewImplementation_00024Companion_##name(__VA_ARGS__)

LADYBIRD_JNI_STATIC(void, nativeClassInit, JNIEnv* env, jobject)
{
    auto local_class = env->FindClass("org/serenityos/ladybird/WebViewImplementation");
    VERIFY(local_class);
    RemoteViewAndroid::cache_java_methods(env, local_class);
    env->DeleteLocalRef(local_class);
}

// Turns omnibox text into a URL to load: a URL as-is, a host name with https:// added, or a search query.
LADYBIRD_JNI_STATIC(jstring, nativeSanitizeURL, JNIEnv* env, jobject, jstring text, jstring search_engine_name)
{
    auto location = string_from_jstring(env, text);
    auto engine = find_search_engine(string_from_jstring(env, search_engine_name));

    auto trimmed = location.bytes_as_string_view().trim_whitespace();
    if (trimmed.is_empty())
        return nullptr;

    JavaEnvironment jenv(global_vm);
    auto search = [&]() -> jstring {
        if (!engine.has_value())
            return nullptr;
        auto url = URL::Parser::basic_parse(engine->format_search_query_for_navigation(trimmed));
        return url.has_value() ? jenv.jstring_from_ak_string(url->to_string()) : nullptr;
    };

    // Text with spaces is always a search.
    if (trimmed.contains(' '))
        return search();

    // Text with a scheme (https://..., about:..., data:...) goes to the regular URL logic.
    bool has_scheme = trimmed.contains("://"sv) || trimmed.starts_with("about:"sv) || trimmed.starts_with("data:"sv) || trimmed.starts_with("file:"sv);
    if (!has_scheme) {
        // Otherwise it's an address only if it starts with a host that has a real public suffix ("example.com/path"),
        // or is localhost or an IP address. Anything else is searched for, as in Chrome.
        auto host_part = trimmed.find_first_split_view('/');
        auto host = host_part.find_first_split_view(':');
        bool is_ip = !host.is_empty() && all_of(host, [](char c) { return is_ascii_digit(c) || c == '.'; }) && host.contains('.');
        bool is_localhost = host.equals_ignoring_ascii_case("localhost"sv);
        bool has_public_suffix = false;
        if (auto last_dot = host.find_last('.'); last_dot.has_value() && *last_dot > 0 && *last_dot + 1 < host.length()) {
            auto top_level_domain = host.substring_view(*last_dot + 1).to_ascii_lowercase_string();
            has_public_suffix = URL::PublicSuffixData::is_matching_public_suffix(top_level_domain.bytes_as_string_view(), URL::PublicSuffixData::IncludeStarRule::Yes);
        }
        if (!is_ip && !is_localhost && !has_public_suffix)
            return search();
        auto scheme = (is_ip || is_localhost) ? "http"sv : "https"sv;
        auto url = URL::Parser::basic_parse(ByteString::formatted("{}://{}", scheme, trimmed));
        if (url.has_value())
            return jenv.jstring_from_ak_string(url->to_string());
        return search();
    }

    auto url = WebView::sanitize_url(trimmed, engine);
    if (!url.has_value())
        return search();
    return jenv.jstring_from_ak_string(url->to_string());
}

LADYBIRD_JNI_STATIC(jobjectArray, nativeSearchEngineNames, JNIEnv* env, jobject)
{
    auto engines = WebView::builtin_search_engines();
    auto string_class = env->FindClass("java/lang/String");
    auto result = env->NewObjectArray(engines.size(), string_class, nullptr);
    JavaEnvironment jenv(global_vm);
    for (size_t i = 0; i < engines.size(); ++i) {
        auto name = jenv.jstring_from_ak_string(engines[i].name);
        env->SetObjectArrayElement(result, i, name);
        env->DeleteLocalRef(name);
    }
    env->DeleteLocalRef(string_class);
    return result;
}

static StringView user_agent_for(bool mobile)
{
    return mobile ? *WebView::user_agents.get("Chrome Android Mobile"sv) : Web::default_user_agent;
}

LADYBIRD_JNI_STATIC(jstring, nativeUserAgent, JNIEnv*, jobject, jboolean mobile)
{
    JavaEnvironment jenv(global_vm);
    return jenv.jstring_from_ak_string(MUST(String::from_utf8(user_agent_for(mobile))));
}

// The user agent is the browser's, not a tab's, so this changes it for every tab.
LADYBIRD_JNI_STATIC(void, nativeSetMobileUserAgent, JNIEnv*, jobject, jboolean mobile)
{
    HubConnection::the().async_set_user_agent_string(MUST(String::from_utf8(user_agent_for(mobile))));
}

// Forgets the cookies and site storage of private tabs. Called when the last private tab closes.
LADYBIRD_JNI_STATIC(void, nativeClearPrivateBrowsingData, JNIEnv*, jobject)
{
    HubConnection::the().async_reset_private_browsing_session();
}

LADYBIRD_JNI(jlong, nativeObjectInit, JNIEnv* env, jobject thiz, jboolean is_private, jlong existing_view_id, jfloat device_pixel_ratio, jfloat refresh_rate)
{
    auto ref = env->NewGlobalRef(thiz);
    Optional<u64> view_id;
    if (existing_view_id >= 0)
        view_id = static_cast<u64>(existing_view_id);
    auto* view = new RemoteViewAndroid(ref, is_private ? WebView::IsPrivate::Yes : WebView::IsPrivate::No, view_id, device_pixel_ratio, refresh_rate);
    return reinterpret_cast<jlong>(view);
}

LADYBIRD_JNI(void, nativeObjectDispose, JNIEnv*, jobject, jlong instance)
{
    delete &view_from(instance);
}

// What a page names a tab by when it opens a view in it, which here is the view's ID in the Hub.
LADYBIRD_JNI(jlong, nativeViewId, JNIEnv*, jobject, jlong instance)
{
    return static_cast<jlong>(view_from(instance).view_id());
}

LADYBIRD_JNI(void, nativeDrawIntoBitmap, JNIEnv* env, jobject, jlong instance, jobject bitmap)
{
    AndroidBitmapInfo info {};
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels)
        return;
    view_from(instance).copy_frame_to_rgba(static_cast<u8*>(pixels), info.stride, info.width, info.height);
    AndroidBitmap_unlockPixels(env, bitmap);
}

LADYBIRD_JNI(void, nativeSetViewportGeometry, JNIEnv*, jobject, jlong instance, jint w, jint h)
{
    view_from(instance).set_viewport_size(w, h);
}

LADYBIRD_JNI(void, nativeSetDevicePixelRatio, JNIEnv*, jobject, jlong instance, jfloat ratio)
{
    view_from(instance).set_device_pixel_ratio(ratio);
}

LADYBIRD_JNI(void, nativeSetHasFocus, JNIEnv*, jobject, jlong instance, jboolean has_focus)
{
    view_from(instance).set_has_focus(has_focus);
}

LADYBIRD_JNI(void, nativeSetVisible, JNIEnv*, jobject, jlong instance, jboolean visible)
{
    view_from(instance).set_visible(visible);
}

LADYBIRD_JNI(void, nativeLoadURL, JNIEnv* env, jobject, jlong instance, jstring url)
{
    view_from(instance).load(string_from_jstring(env, url));
}

LADYBIRD_JNI(void, nativeReload, JNIEnv*, jobject, jlong instance)
{
    view_from(instance).reload();
}

LADYBIRD_JNI(void, nativeStopLoading, JNIEnv*, jobject, jlong instance)
{
    view_from(instance).stop_loading();
}

LADYBIRD_JNI(void, nativeTraverseHistory, JNIEnv*, jobject, jlong instance, jint delta)
{
    view_from(instance).traverse_history(delta);
}

// direction: 1 = zoom in, -1 = zoom out, 0 = reset. Returns the zoom level from before the change, which is what the
// view knows of until the Hub tells it the new one.
LADYBIRD_JNI(jfloat, nativeZoom, JNIEnv*, jobject, jlong instance, jint direction)
{
    auto& view = view_from(instance);
    view.zoom(direction);
    return static_cast<jfloat>(view.state().zoom_level);
}

// Pinch zoom, which the page shows by scaling its visual viewport.
LADYBIRD_JNI(void, nativePinch, JNIEnv*, jobject, jlong instance, jfloat focus_x, jfloat focus_y, jfloat scale_delta, jboolean continues_gesture)
{
    view_from(instance).pinch(focus_x, focus_y, scale_delta, continues_gesture);
}

// A step of a touch scroll, or with `ended`, the lift of the finger, after which the Compositor flings if it was a flick.
LADYBIRD_JNI(void, nativeScrollGesture, JNIEnv*, jobject, jlong instance, jboolean ended, jfloat x, jfloat y, jfloat raw_x, jfloat raw_y, jfloat delta_x, jfloat delta_y)
{
    view_from(instance).scroll_gesture(ended ? Web::ScrollGesturePhase::Ended : Web::ScrollGesturePhase::Ongoing, x, y, raw_x, raw_y, delta_x, delta_y);
}

// Mouse event types, mirrored in WebViewImplementation.kt.
LADYBIRD_JNI(void, nativeMouseEvent, JNIEnv*, jobject, jlong instance, jint event_type, jfloat x, jfloat y, jfloat raw_x, jfloat raw_y, jint button, jint wheel_delta_x, jint wheel_delta_y)
{
    auto& view = view_from(instance);
    auto mouse_button = static_cast<Web::UIEvents::MouseButton>(button);
    switch (event_type) {
    case 0:
        view.mouse_event(Web::MouseEvent::Type::MouseDown, x, y, raw_x, raw_y, mouse_button, 0, 0, true, 1);
        break;
    case 1:
        view.mouse_event(Web::MouseEvent::Type::MouseUp, x, y, raw_x, raw_y, mouse_button, 0, 0, true, 1);
        break;
    case 2:
        view.mouse_event(Web::MouseEvent::Type::MouseMove, x, y, raw_x, raw_y, mouse_button, 0, 0, true, 0);
        break;
    case 3:
        view.mouse_event(Web::MouseEvent::Type::MouseWheel, x, y, raw_x, raw_y, Web::UIEvents::MouseButton::None, wheel_delta_x, wheel_delta_y, false, 0);
        break;
    case 4:
        // A double tap, which selects a word as a double click does.
        view.mouse_event(Web::MouseEvent::Type::MouseDown, x, y, raw_x, raw_y, mouse_button, 0, 0, true, 2);
        view.mouse_event(Web::MouseEvent::Type::MouseUp, x, y, raw_x, raw_y, mouse_button, 0, 0, true, 2);
        break;
    default:
        break;
    }
}

LADYBIRD_JNI(void, nativeKeyEvent, JNIEnv*, jobject, jlong instance, jboolean is_down, jint key, jint modifiers, jint code_point)
{
    view_from(instance).key_event(
        is_down ? Web::KeyEvent::Type::KeyDown : Web::KeyEvent::Type::KeyUp,
        static_cast<Web::UIEvents::KeyCode>(key),
        static_cast<Web::UIEvents::KeyModifier>(modifiers),
        static_cast<u32>(code_point));
}

LADYBIRD_JNI(void, nativeCommitText, JNIEnv* env, jobject, jlong instance, jstring text)
{
    view_from(instance).commit_text(Utf16String::from_utf8(string_from_jstring(env, text)));
}

LADYBIRD_JNI(void, nativeAlertClosed, JNIEnv*, jobject, jlong instance)
{
    view_from(instance).alert_closed();
}

LADYBIRD_JNI(void, nativeConfirmClosed, JNIEnv*, jobject, jlong instance, jboolean accepted)
{
    view_from(instance).confirm_closed(accepted);
}

LADYBIRD_JNI(void, nativePromptClosed, JNIEnv* env, jobject, jlong instance, jstring response)
{
    Optional<Utf16String> value;
    if (response)
        value = Utf16String::from_utf8(string_from_jstring(env, response));
    view_from(instance).prompt_closed(move(value));
}

LADYBIRD_JNI(void, nativeSelectDropdownClosed, JNIEnv*, jobject, jlong instance, jint selected_id)
{
    Optional<u32> value;
    if (selected_id >= 0)
        value = static_cast<u32>(selected_id);
    view_from(instance).select_dropdown_closed(value);
}

LADYBIRD_JNI(void, nativeFindInPage, JNIEnv* env, jobject, jlong instance, jstring query)
{
    view_from(instance).find_in_page(Utf16String::from_utf8(string_from_jstring(env, query)));
}

LADYBIRD_JNI(void, nativeFindInPageNext, JNIEnv*, jobject, jlong instance)
{
    view_from(instance).find_in_page_next();
}

LADYBIRD_JNI(void, nativeFindInPagePrevious, JNIEnv*, jobject, jlong instance)
{
    view_from(instance).find_in_page_previous();
}

LADYBIRD_JNI(void, nativeFindInPageEnd, JNIEnv*, jobject, jlong instance)
{
    view_from(instance).find_in_page_end();
}

// surface is the Java view's Surface (or null when it goes away). Frames are presented straight into it.
LADYBIRD_JNI(void, nativeSetSurface, JNIEnv* env, jobject, jlong instance, jobject surface)
{
    view_from(instance).set_surface(surface ? ANativeWindow_fromSurface(env, surface) : nullptr);
}

LADYBIRD_JNI(void, nativeSetPresentationPaused, JNIEnv*, jobject, jlong instance, jboolean paused)
{
    view_from(instance).set_presentation_paused(paused);
}
