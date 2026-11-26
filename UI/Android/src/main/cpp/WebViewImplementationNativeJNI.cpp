/*
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "JNIHelpers.h"
#include <AK/AllOf.h>
#include <AK/CharacterTypes.h>
#include "WebViewImplementationNative.h"
#include <LibURL/Parser.h>
#include <LibURL/PublicSuffixData.h>
#include <LibURL/URL.h>
#include <LibWeb/Loader/UserAgent.h>
#include <LibWebView/Application.h>
#include <LibWebView/SearchEngine.h>
#include <LibWebView/URL.h>
#include <LibWebView/UserAgent.h>
#include <android/native_window_jni.h>
#include <jni.h>

using namespace Ladybird;

static WebViewImplementationNative& impl_from(jlong instance)
{
    return *reinterpret_cast<WebViewImplementationNative*>(instance);
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
    WebViewImplementationNative::cache_java_methods(env, local_class);
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
        // Otherwise it's an address only if it starts with a host that has a real public suffix
        // ("example.com/path"), or is localhost or an IP address; anything else is searched, like Chrome does.
        auto host_part = trimmed.find_first_split_view('/');
        auto host = host_part.find_first_split_view(':');
        bool is_ip = !host.is_empty() && all_of(host, [](char c) { return is_ascii_digit(c) || c == '.'; }) && host.contains('.');
        bool is_localhost = host.equals_ignoring_ascii_case("localhost"sv);
        // "name.tld" where tld is a known top-level domain (get_public_suffix() returns the registrable domain, not the suffix).
        bool has_public_suffix = false;
        if (auto last_dot = host.find_last('.'); last_dot.has_value() && *last_dot > 0 && *last_dot + 1 < host.length()) {
            auto tld = host.substring_view(*last_dot + 1).to_ascii_lowercase_string();
            has_public_suffix = URL::PublicSuffixData::the()->is_public_suffix(tld);
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

LADYBIRD_JNI(jlong, nativeObjectInit, JNIEnv* env, jobject thiz, jlong parent_instance, jlong page_index)
{
    auto ref = env->NewGlobalRef(thiz);
    WebViewImplementationNative* instance = nullptr;
    if (parent_instance != 0 && page_index >= 0)
        instance = new WebViewImplementationNative(ref, impl_from(parent_instance), static_cast<u64>(page_index));
    else
        instance = new WebViewImplementationNative(ref);
    return reinterpret_cast<jlong>(instance);
}

LADYBIRD_JNI(void, nativeObjectDispose, JNIEnv* env, jobject, jlong instance)
{
    auto* impl = &impl_from(instance);
    auto java_instance = impl->java_instance();
    delete impl;
    env->DeleteGlobalRef(java_instance);
}

LADYBIRD_JNI(jstring, nativeGetHandle, JNIEnv*, jobject, jlong instance)
{
    JavaEnvironment env(global_vm);
    return env.jstring_from_ak_string(impl_from(instance).handle());
}

LADYBIRD_JNI(void, nativeDrawIntoBitmap, JNIEnv* env, jobject, jlong instance, jobject bitmap)
{
    AndroidBitmapInfo bitmap_info = {};
    void* pixels = nullptr;
    AndroidBitmap_getInfo(env, bitmap, &bitmap_info);
    AndroidBitmap_lockPixels(env, bitmap, &pixels);
    if (pixels)
        impl_from(instance).paint_into_bitmap(pixels, bitmap_info);
    AndroidBitmap_unlockPixels(env, bitmap);
}

LADYBIRD_JNI(void, nativeSetViewportGeometry, JNIEnv*, jobject, jlong instance, jint w, jint h)
{
    impl_from(instance).set_viewport_geometry(w, h);
}

LADYBIRD_JNI(void, nativeSetDevicePixelRatio, JNIEnv*, jobject, jlong instance, jfloat ratio)
{
    impl_from(instance).set_device_pixel_ratio(ratio);
}

LADYBIRD_JNI(void, nativeSetHasFocus, JNIEnv*, jobject, jlong instance, jboolean has_focus)
{
    impl_from(instance).set_has_focus(has_focus);
}

LADYBIRD_JNI(void, nativeSetVisible, JNIEnv*, jobject, jlong instance, jboolean visible)
{
    impl_from(instance).set_visible(visible);
}

LADYBIRD_JNI(void, nativeLoadURL, JNIEnv* env, jobject, jlong instance, jstring url)
{
    auto url_string = string_from_jstring(env, url);
    auto ak_url = URL::create_with_url_or_path(url_string.to_byte_string());
    if (!ak_url.has_value()) {
        warnln("Unable to parse URL '{}'", url_string);
        return;
    }
    impl_from(instance).load(ak_url.release_value());
}

LADYBIRD_JNI(void, nativeReload, JNIEnv*, jobject, jlong instance)
{
    impl_from(instance).reload();
}

LADYBIRD_JNI(void, nativeTraverseHistory, JNIEnv*, jobject, jlong instance, jint delta)
{
    impl_from(instance).traverse_the_history_by_delta(delta);
}

// direction: 1 = zoom in, -1 = zoom out, 0 = reset. Returns the new zoom level.
LADYBIRD_JNI(jfloat, nativeZoom, JNIEnv*, jobject, jlong instance, jint direction)
{
    auto& impl = impl_from(instance);
    if (direction > 0)
        impl.zoom_in();
    else if (direction < 0)
        impl.zoom_out();
    else
        impl.reset_zoom();
    return impl.zoom_level();
}

// Mouse event types, mirrored in WebViewImplementation.kt.
LADYBIRD_JNI(void, nativeMouseEvent, JNIEnv*, jobject, jlong instance, jint event_type, jfloat x, jfloat y, jfloat raw_x, jfloat raw_y, jint button, jint wheel_delta_x, jint wheel_delta_y)
{
    Web::MouseEvent::Type type;
    switch (event_type) {
    case 0:
        type = Web::MouseEvent::Type::MouseDown;
        break;
    case 1:
        type = Web::MouseEvent::Type::MouseUp;
        break;
    case 2:
        type = Web::MouseEvent::Type::MouseMove;
        break;
    case 3:
        type = Web::MouseEvent::Type::MouseWheel;
        break;
    case 4:
        type = Web::MouseEvent::Type::DoubleClick;
        break;
    default:
        return;
    }
    impl_from(instance).mouse_event(type, x, y, raw_x, raw_y, static_cast<Web::UIEvents::MouseButton>(button), wheel_delta_x, wheel_delta_y);
}

LADYBIRD_JNI(void, nativeKeyEvent, JNIEnv*, jobject, jlong instance, jboolean is_down, jint key, jint modifiers, jint code_point)
{
    impl_from(instance).key_event(
        is_down ? Web::KeyEvent::Type::KeyDown : Web::KeyEvent::Type::KeyUp,
        static_cast<Web::UIEvents::KeyCode>(key),
        static_cast<Web::UIEvents::KeyModifier>(modifiers),
        static_cast<u32>(code_point));
}

LADYBIRD_JNI(void, nativeAlertClosed, JNIEnv*, jobject, jlong instance)
{
    impl_from(instance).alert_closed();
}

LADYBIRD_JNI(void, nativeConfirmClosed, JNIEnv*, jobject, jlong instance, jboolean accepted)
{
    impl_from(instance).confirm_closed(accepted);
}

LADYBIRD_JNI(void, nativePromptClosed, JNIEnv* env, jobject, jlong instance, jstring response)
{
    Optional<String> value;
    if (response)
        value = string_from_jstring(env, response);
    impl_from(instance).prompt_closed(value);
}

LADYBIRD_JNI(void, nativeSelectDropdownClosed, JNIEnv*, jobject, jlong instance, jint selected_id)
{
    Optional<u32> value;
    if (selected_id >= 0)
        value = static_cast<u32>(selected_id);
    impl_from(instance).select_dropdown_closed(value);
}

// scheme: 0 = follow the system, 1 = dark, 2 = light.
LADYBIRD_JNI(void, nativeSetPreferredColorScheme, JNIEnv*, jobject, jlong instance, jint scheme)
{
    auto value = scheme == 1 ? Web::CSS::PreferredColorScheme::Dark : scheme == 2 ? Web::CSS::PreferredColorScheme::Light
                                                                               : Web::CSS::PreferredColorScheme::Auto;
    impl_from(instance).set_preferred_color_scheme(value);
}

LADYBIRD_JNI(void, nativeSetMobileUserAgent, JNIEnv*, jobject, jlong instance, jboolean mobile)
{
    auto user_agent = mobile ? *WebView::user_agents.get("Chrome Android Mobile"sv) : Web::default_user_agent;
    impl_from(instance).debug_request("spoof-user-agent", user_agent);
}

// Sets the zoom level directly (pinch to zoom). Returns the level actually applied, after clamping.
LADYBIRD_JNI(jfloat, nativeSetZoom, JNIEnv*, jobject, jlong instance, jfloat level)
{
    auto& impl = impl_from(instance);
    impl.set_zoom(level);
    return impl.zoom_level();
}

LADYBIRD_JNI(void, nativeFindInPage, JNIEnv* env, jobject, jlong instance, jstring query)
{
    impl_from(instance).find_in_page(string_from_jstring(env, query));
}

LADYBIRD_JNI(void, nativeFindInPageNext, JNIEnv*, jobject, jlong instance)
{
    impl_from(instance).find_in_page_next_match();
}

LADYBIRD_JNI(void, nativeFindInPagePrevious, JNIEnv*, jobject, jlong instance)
{
    impl_from(instance).find_in_page_previous_match();
}

// Must be called before the view loads anything: a private view's process gets its own in-memory cookie and storage jars.
LADYBIRD_JNI(void, nativeSetPrivateBrowsing, JNIEnv*, jobject, jlong instance, jboolean private_browsing)
{
    impl_from(instance).set_private_browsing(private_browsing);
}

// The Cookie header this view's page would send to url, so the download manager can fetch it as the page would.
LADYBIRD_JNI(jstring, nativeCookiesForURL, JNIEnv* env, jobject, jlong instance, jstring url)
{
    auto parsed = URL::Parser::basic_parse(string_from_jstring(env, url));
    if (!parsed.has_value())
        return nullptr;
    auto cookies = impl_from(instance).cookies_for_url(*parsed);
    if (cookies.is_empty())
        return nullptr;
    JavaEnvironment jenv(global_vm);
    return jenv.jstring_from_ak_string(cookies);
}

LADYBIRD_JNI_STATIC(jstring, nativeUserAgent, JNIEnv*, jobject, jboolean mobile)
{
    auto user_agent = mobile ? *WebView::user_agents.get("Chrome Android Mobile"sv) : Web::default_user_agent;
    JavaEnvironment jenv(global_vm);
    return jenv.jstring_from_ak_string(MUST(String::from_utf8(user_agent)));
}

// Forgets the cookies and site storage of incognito tabs. Called when the last incognito tab closes.
LADYBIRD_JNI_STATIC(void, nativeClearPrivateBrowsingData, JNIEnv*, jobject)
{
    WebView::Application::clear_private_browsing_data();
}

// surface is the Java view's Surface (or null when it goes away); frames are presented straight into it.
LADYBIRD_JNI(void, nativeSetSurface, JNIEnv* env, jobject, jlong instance, jobject surface)
{
    impl_from(instance).set_surface(surface ? ANativeWindow_fromSurface(env, surface) : nullptr);
}

LADYBIRD_JNI(void, nativeSetPresentationPaused, JNIEnv*, jobject, jlong instance, jboolean paused)
{
    impl_from(instance).set_presentation_paused(paused);
}
